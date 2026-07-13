# RabbitMQ 源码深度解析

> 基于 RabbitMQ Server 主线源码（rabbitmq-server/deps/rabbit/src/ 及 rabbit_common/src/），逐模块深挖核心机制的设计动机、精确行为与边界情况。每章聚焦 1-2 个最关键特性，以"这段代码究竟在做什么，为什么要这样做，出了问题会在哪里卡住"为主线展开。

---

## 第一章：连接与 Channel 模型——credit_flow 的背压闭环

### 1.1 为什么 Reader 不用 gen_server

`rabbit_reader` 是整条连接的入口进程，负责从 TCP socket 读帧、解析、分发到 Channel 进程。它刻意**不使用 gen_server**，而是通过 `proc_lib:spawn_link` 启动后手工实现 `system_continue/3`、`system_terminate/4` 等 OTP 系统消息接口。

这个决定的根本原因是：gen_server 的消息循环会把 socket 数据和进程消息混在同一个 mailbox 里串行处理，而 Reader 需要**精细控制何时从 socket 消费数据**。具体体现在 `recvloop/mainloop` 的双层循环里：

```erlang
%% rabbit_reader.erl
recvloop(Deb, Buf, BufLen, State = #v1{pending_recv = true}) ->
    mainloop(Deb, Buf, BufLen, State);        %% 缓冲区数据不足，回到外层等新数据
recvloop(Deb, Buf, BufLen, State = #v1{connection_state = blocked}) ->
    mainloop(Deb, Buf, BufLen, State);        %% ← 关键：连接被阻塞时停止消费帧
recvloop(Deb, [B], _BufLen, State) ->
    {Rest, State1} = handle_input(State#v1.callback, B, State),
    recvloop(Deb, [Rest], size(Rest), State1);
```

当 `connection_state = blocked` 时，`recvloop` 直接跳到 `mainloop`，不再消费缓冲区里的帧。`mainloop` 等待新的 socket 数据（`{active, once}`），新数据到来时继续跑 `recvloop`，但 `connection_state` 仍然是 `blocked`，于是又立即跳回 `mainloop`。这样，Reader 进程会不断收到操作系统交付的数据，却**一帧都不处理**。TCP 接收缓冲区逐渐填满，内核开始发出 ACK 时通告窗口为 0，发布方的 write() 系统调用阻塞——这就是最终的 TCP 背压。

### 1.2 credit_flow 的进程字典设计

`credit_flow.erl` 是整套背压的核心，它**全部运行在调用方的进程字典里**，没有自己的进程。这是一个刻意的设计：进程字典读写是 O(1) 本地操作，完全无锁，不需要进程间通信，开销极低。

每一对（发送方 Pid A，接收方 Pid B）之间有两个独立的信用计数，分别存在各自的进程字典里：

- 在 A 的进程字典：`{credit_from, B} → 剩余可发送信用`（A 追踪自己还能向 B 发多少）
- 在 B 的进程字典：`{credit_to, A} → 距离下一次 grant 还需处理多少条`（B 追踪何时还信用给 A）

`?UPDATE` 宏是访问进程字典的统一入口，它故意不用函数（HOF），而用宏，就是为了避免闭包创建带来的额外内存分配：

```erlang
-define(UPDATE(Key, Default, Var, Expr),
        begin
            Var = case get(Key) of
                undefined -> Default;
                V         -> V
            end,
            put(Key, Expr)
        end).
```

### 1.3 send/2：信用递减与阻塞触发

`credit_flow:send(From)` 在 **Reader 进程**里调用，`From` 是 Channel 的 Pid：

```erlang
send(From, {InitialCredit, _MoreCreditAfter}) ->
    ?UPDATE({credit_from, From}, InitialCredit, C,
            if C =:= 1 -> block(From),   %% 信用即将耗尽，阻塞
                          0;
               true    -> C - 1
            end).
```

注意边界：阻塞触发在 `C =:= 1`，即**发送本条消息后信用变 0**。此时调用 `block(From)`：

```erlang
block(From) ->
    case blocked() of
        false -> put(credit_blocked_at, erlang:monotonic_time()); %% 记录首次阻塞时间戳
        true  -> ok
    end,
    ?UPDATE(credit_blocked, [], Blocks, [From | Blocks]).
```

`block/1` 把 `From`（Channel Pid）加入 Reader 进程字典的 `credit_blocked` 列表。**没有任何消息发送**，纯粹是写进程字典。`blocked()` 函数就是检查这个列表是否为空：

```erlang
blocked() -> case get(credit_blocked) of
                 undefined -> false;
                 []        -> false;
                 _         -> true
             end.
```

### 1.4 control_throttle：把 credit_blocked 转化为 TCP 停读

`send/1` 完成后，调用方（Reader 的 `process_frame`）会调用 `control_throttle`。这里是真正把信用不足转化为连接阻塞的关键函数：

```erlang
control_throttle(State = #v1{connection_state = CS,
                             throttle = #throttle{blocked_by = Reasons} = Throttle}) ->
    Throttle1 = case credit_flow:blocked() of
                  true  -> Throttle#throttle{blocked_by = sets:add_element(flow, Reasons)};
                  false -> Throttle#throttle{blocked_by = sets:del_element(flow, Reasons)}
                end,
    State1 = State#v1{throttle = Throttle1},
    case CS of
        running -> maybe_block(State1);
        blocked -> maybe_block(maybe_unblock(State1));
        _       -> State1
    end.
```

它做两件事：一是把 `credit_flow:blocked()` 的结果翻译成 `blocked_by` 集合里的 `flow` 元素（此集合还会被内存告警/磁盘告警往里写，任意一个元素存在就会阻塞）；二是调用 `maybe_block/1`。

`maybe_block/1` 在 `should_block_connection` 返回 true 时，把 `connection_state` 切换为 `blocked`，同时暂停心跳监控（避免阻塞状态下心跳超时误关连接），并向有能力的客户端发送 `connection.blocked` 帧：

```erlang
maybe_block(State = #v1{connection_state = CS, throttle = Throttle}) ->
    case should_block_connection(Throttle) of
        true ->
            State1 = State#v1{connection_state = blocked,
                              throttle = update_last_blocked_at(Throttle)},
            case CS of
                running -> ok = rabbit_heartbeat:pause_monitor(State#v1.heartbeater);
                _       -> ok
            end,
            maybe_send_blocked_or_unblocked(State1);
        false -> State
    end.
```

`should_block_connection` 要求 `should_block = true`（即曾收到过 publish 帧）且 `blocked_by` 非空。这个 `should_block` 字段的设计是为了区分**只消费不发布的连接**——纯消费者连接即使内存告警也不应该被阻塞，因为阻止消费会加剧积压。

### 1.5 ack/2：信用归还与 grant 发送

Channel 每处理完一条带 `flow` 标记的消息，就调用 `credit_flow:ack(Reader)`，这在 **Channel 进程**里执行，`Reader` 是所属 Reader 进程的 Pid：

```erlang
%% rabbit_channel.erl – handle_cast({method, Method, Content, Flow}, ...)
case Flow of
    flow   -> credit_flow:ack(Reader);   %% ← 在 Channel 进程字典里操作
    noflow -> ok
end,
```

```erlang
ack(To, {_InitialCredit, MoreCreditAfter}) ->
    ?UPDATE({credit_to, To}, MoreCreditAfter, C,
            if C =:= 1 -> grant(To, MoreCreditAfter),  %% 处理满 MoreCreditAfter 条，发 grant
                          MoreCreditAfter;
               true    -> C - 1
            end).
```

默认 `MoreCreditAfter = 50`，即 Channel 每处理 50 条 flow 消息后，调用 `grant(Reader, 50)`：

```erlang
grant(To, Quantity) ->
    Msg = {bump_credit, {self(), Quantity}},
    case blocked() of
        false -> To ! Msg;                       %% Channel 自身未被阻塞，立即发消息给 Reader
        true  -> ?UPDATE(credit_deferred, [], Deferred, [{To, Msg} | Deferred])
    end.
```

这里有一个**容易被忽视的细节**：`grant` 先检查 `blocked()`——这检查的是 **Channel 进程自身**是否被别人阻塞（例如队列进程阻塞了 Channel）。如果 Channel 自己也在被阻塞，它不会立即发 `bump_credit` 消息给 Reader，而是存入 `credit_deferred` 列表。这防止了"下游堵塞、中游无视"的场景：队列处理不过来 → Channel 被阻塞 → Channel 暂停还信用给 Reader → Reader 也停下来。等到 Channel 自身被 unblock 时，`unblock/1` 会统一把 `credit_deferred` 里的消息全部发出去：

```erlang
unblock(From) ->
    ?UPDATE(credit_blocked, [], Blocks, Blocks -- [From]),
    case blocked() of
        false ->
            case erase(credit_deferred) of
                undefined -> ok;
                Credits   -> lists:foreach(fun({To, Msg}) -> To ! Msg end, Credits)
            end;
        true -> ok
    end.
```

### 1.6 handle_bump_msg：Reader 的解锁路径

Reader 收到 `{bump_credit, Msg}` 消息时（这是进程间通信，走正常 mailbox）：

```erlang
%% rabbit_reader.erl
handle_other({bump_credit, Msg}, State) ->
    credit_flow:handle_bump_msg(Msg),
    control_throttle(State);
```

```erlang
handle_bump_msg({From, MoreCredit}) ->
    ?UPDATE({credit_from, From}, 0, C,
            if C =< 0 andalso C + MoreCredit > 0 -> unblock(From),
                                                    C + MoreCredit;
               true                              -> C + MoreCredit
            end).
```

条件 `C =< 0 andalso C + MoreCredit > 0` 精确刻画了"信用从耗尽变为充足"的瞬间。只有这一刻才触发 `unblock(From)`，将 Channel Pid 从 `credit_blocked` 列表里移除。随后 `control_throttle` 重新评估：若 `credit_blocked` 变空（且其他阻塞原因也消失），`maybe_unblock` 会把 `connection_state` 切回 `running`，心跳恢复监控，并向客户端发 `connection.unblocked`——下一次 `recvloop` 就不再直接跳过，TCP 数据重新流动。

### 1.7 完整闭环时序

```
[发布方] ──TCP──▶ [Reader 进程]                       [Channel 进程]
                     │                                     │
  读帧: process_frame │                                     │
  ──────────────────▶│                                     │
                     │ do_flow(ChPid, Method, Content)     │
                     │   credit_flow:send(ChPid)           │
                     │   进程字典: credit_from[ChPid] -= 1 │
                     │   (如减至0): block(ChPid)           │
                     │              credit_blocked=[ChPid] │
                     │   gen_server2:cast(ChPid, {method,  │
                     │     Method, Content, flow})         │
                     │──────────────────────────────────▶ │
                     │ control_throttle()                  │ handle_cast:
                     │   credit_flow:blocked() = true      │   flow: credit_flow:ack(Reader)
                     │   blocked_by ∪= {flow}              │   进程字典: credit_to[Reader] -= 1
                     │   connection_state := blocked       │   (每50条): grant(Reader, 50)
                     │                                     │     blocked()=false:
                     │                                 {bump_credit, {ChPid,50}} !
                     │◀────────────────────────────────── │
                     │ handle_other({bump_credit, Msg}):   │
                     │   handle_bump_msg:                  │
                     │   credit_from[ChPid] 从≤0→>0       │
                     │   unblock(ChPid)                    │
                     │   credit_blocked=[]                 │
                     │ control_throttle():                 │
                     │   blocked()=false                   │
                     │   blocked_by -= {flow}              │
                     │   connection_state := running       │
  recvloop 继续消费帧 │                                     │
```

整个链路没有任何共享内存，没有锁，没有 ETS。信用状态分散在各进程的进程字典里，协作通过极少量的 `bump_credit` 消息完成。这是 Erlang Actor 模型用于流控的教科书式实现。

### 1.8 进程树：为什么 Channel 崩溃不影响连接

每条连接的进程树：

```
rabbit_connection_sup (one_for_all)
├── rabbit_reader          ← 特殊进程，连接生命周期的核心
└── rabbit_connection_helper_sup (one_for_one)
    ├── rabbit_queue_collector   ← exclusive 队列注册表
    ├── rabbit_heartbeat         ← 心跳进程
    └── rabbit_channel_sup_sup (simple_one_for_one)
        └── rabbit_channel_sup (one_for_all) × M
            ├── rabbit_channel   ← gen_server2
            ├── rabbit_writer    ← 帧发送进程
            └── rabbit_limiter   ← QoS prefetch 控制
```

`rabbit_channel_sup` 使用 `one_for_all`：Channel、Writer、Limiter 任一崩溃，三个进程一起终止。这不是重启而是关闭——Channel 崩溃意味着该 Channel 的状态不可恢复（未 ack 的消息需要 requeue，未发完的 confirm 需要 nack），直接关闭比重启更安全。

Reader 通过 `monitor` 监视每个 Channel Pid（存在进程字典 `{channel, N}` 里），收到 `DOWN` 消息后将对应 Channel 从字典移除，并向客户端发送 `channel.close`。Reader 自身不会因为 Channel 崩溃而退出——`helper_sup` 的 `one_for_one` 保证了这一点。

### 1.9 state/0：管理界面的 "in flow" 指示器原理

管理插件里连接状态偶尔出现 `"flow"` 而非 `"running"`，这个状态由 `credit_flow:state/0` 计算：

```erlang
%% credit_flow.erl
-define(STATE_CHANGE_INTERVAL, 1_000_000).   %% 1 秒，单位 microsecond

state() ->
    case blocked() of
        true  -> flow;
        false -> state_delayed(get(credit_blocked_at))
    end.

state_delayed(BlockedAt) ->
    case BlockedAt of
        undefined -> running;
        B ->
            Now  = erlang:monotonic_time(),
            Diff = erlang:convert_time_unit(Now - B, native, microsecond),
            case Diff < ?STATE_CHANGE_INTERVAL of
                true  -> flow;
                false -> running
            end
    end.
```

关键设计：即使连接此刻已经解锁（`blocked() = false`），只要距上次被阻塞的时间戳不足 1 秒，`state/0` 仍然返回 `flow`。这是一个"粘滞延迟"设计——管理界面的刷新周期通常是 5 秒，如果解锁后立即切回 `running`，用户会看到指示器快速闪烁，感知不到实际发生过的背压。`credit_blocked_at` 通过 `block/1` 写入，只在首次阻塞时记录（`case blocked() of false -> put(...); true -> ok end`），不会被后续阻塞事件覆盖。

### 1.10 peer_down/1：节点宕机时的信用状态清理

当 Channel 所在节点宕机（或 Channel 进程意外消亡），Reader 需要清理对应的信用状态，否则进程字典里会一直留着已死进程的 `{credit_from, DeadPid}` 条目：

```erlang
%% credit_flow.erl
peer_down(Peer) ->
    %% In theory we could also remove it from credit_deferred here, but it
    %% doesn't really matter; at some point later we will drain
    %% credit_deferred and thus send messages into the void...
    unblock(Peer),
    erase({credit_from, Peer}),
    erase({credit_to, Peer}),
    ok.
```

注释里坦然承认了一个妥协：`credit_deferred` 里如果已经有了该 Peer 的 `{bump_credit, ...}` 消息，`peer_down` 不会清理它。等到 `unblock` 之后触发 `credit_deferred` 排空时，这些消息会发送到已死的 Pid，`!` 操作符不会报错（Erlang 的 fire-and-forget 语义），只是消息进了 void。这是一个刻意接受的 trade-off：清理 `credit_deferred` 需要扫描整个列表，代价大于直接让消息丢失。

`peer_down` 在 Reader 收到 Channel 的 `{'DOWN', ...}` 消息时调用（具体是 `rabbit_reader` 的 `handle_other` 处理 `{'DOWN', _, process, ChPid, _}` 分支），也在 `rabbit_channel` 收到队列进程宕机通知时调用（对队列进程的信用关系做对称清理）。

---

## 第二章：Exchange 路由——topic Trie 的 V4 双表设计

### 2.1 路由的核心问题

topic exchange 的挑战在于：binding key 包含通配符（`*` 匹配一个词，`#` 匹配零或多个词），路由时需要判断 routing key 是否匹配某个 binding 模式。这是一个字符串集合的成员关系判断问题，naive 实现是遍历所有 binding 逐一匹配，时间复杂度 O(B×L)（B = binding 数，L = routing key 词数），不可接受。

RabbitMQ 的解法是把所有 binding key 共同构建一棵前缀树（Trie），按词分层，让路由时间从 O(B×L) 降到 O(L × 分支因子)，与 binding 数量无关。

### 2.2 V4 双表结构

当前默认的 V4 实现（feature flag `topic_binding_projection_v4` 启用后）用两张 ETS 表代替了老版本的单一 ETS 记录表：

**边表（`rabbit_khepri_topic_trie_v4`，`set` 类型）**

每一行是一条 Trie 边：

```
Key   = {XSrc, ParentNodeId, Word}       %% XSrc = {VHost, ExchangeName}
Value = {Key, ChildNodeId, ChildCount}   %% ChildCount = 该子节点的出边数量
```

`ChildCount` 是 GC 的关键字段（后面展开）。`ets:lookup_element(Tab, Key, 2, undefined)` 可以直接拿到 `ChildNodeId`，一次哈希查表，O(1)。

**叶子 binding 表（`rabbit_khepri_topic_binding_v4`，`ordered_set` 类型）**

每一行是一个叶子节点处的 binding：

```
Key = {NodeId, BindingKey, Dest}         %% Dest = 目标队列/exchange 资源
```

使用 `ordered_set` 而非 `set` 是因为需要范围扫描：给定 `NodeId`，找到该节点下的所有 binding，可以用 `ets:next({NodeId, <<>>, {}})` 做有序前缀扫描，不需要知道有多少条。

### 2.3 trie_match 递归：三路分支与 `#` 的处理

路由入口从 `root` 节点开始，每一层有三路分支：

```erlang
trie_match(XSrc, Node, [W | RestW] = Words, BKeys, Acc0) ->
    %% 分支1：精确匹配当前词
    Acc1 = trie_match_try(XSrc, Node, W,       fun trie_match/5,          RestW, BKeys, Acc0),
    %% 分支2：'*' 通配符（匹配任意单词）
    Acc2 = trie_match_try(XSrc, Node, <<"*">>, fun trie_match/5,          RestW, BKeys, Acc1),
    %% 分支3：'#' 通配符（匹配零或多个词）
    trie_match_try(XSrc, Node, <<"#">>,        fun trie_match_skip_any/5, Words, BKeys, Acc2).

trie_match_try(XSrc, Node, Word, MatchFun, RestW, BKeys, Acc) ->
    case ets:lookup_element(?TOPIC_TRIE_PROJECTION, {XSrc, Node, Word}, 2, undefined) of
        undefined -> Acc;         %% 边不存在，直接返回，不进入无效递归
        NextNode  -> MatchFun(XSrc, NextNode, RestW, BKeys, Acc)
    end.
```

`trie_match_skip_any` 处理 `#` 的语义（匹配零或多个词）：

```erlang
trie_match_skip_any(XSrc, Node, [], BKeys, Acc) ->
    trie_match(XSrc, Node, [], BKeys, Acc);           %% 已到末尾，尝试收集 binding
trie_match_skip_any(XSrc, Node, [_ | RestW] = Words, BKeys, Acc) ->
    trie_match_skip_any(
      XSrc, Node, RestW, BKeys,
      trie_match(XSrc, Node, Words, BKeys, Acc)).     %% 每跳过一个词，也尝试从当前位置继续匹配
```

`#` 的关键在于它可以匹配**从当前位置到任意位置的所有词**。`trie_match_skip_any` 在跳过每个词时，都会对当前剩余词列表调用一次 `trie_match`——这处理了类似 `a.#.c` 匹配 `a.b.b.c` 这样的情形（`#` 跳过 `b.b`，然后 `c` 继续匹配）。

词语耗尽时还要额外尝试 `#`（因为 `#` 可以匹配 0 个词）：

```erlang
trie_match(XSrc, Node, [], BKeys, Acc0) ->
    Acc1 = trie_bindings(Node, BKeys, Acc0),          %% 收集当前节点的 binding
    trie_match_try(XSrc, Node, <<"#">>,               %% 还要再试一次 '#'（匹配 0 个词）
                   fun trie_match_skip_any/5, [], BKeys, Acc1).
```

这处理了 `a.#` 应该匹配 routing key `a` 的情形——routing key 词耗尽后，节点上如果有 `#` 边指向的子节点，该子节点在 `trie_match_skip_any([])` 里还会调用 `trie_match([], ...)` 收集 binding。

### 2.4 trie_bindings：小 fanout 快路径

在叶子节点收集 binding 时，代码专门为 fanout ≤ 2 的常见情况做了优化：

```erlang
trie_bindings(NodeId, BKeys, Acc) ->
    StartKey = {NodeId, <<>>, {}},
    case ets:next(?TOPIC_BINDING_PROJECTION, StartKey) of
        {NodeId, BKey1, Dest1} = Key1 ->
            case ets:next(?TOPIC_BINDING_PROJECTION, Key1) of
                {NodeId, BKey2, Dest2} = Key2 ->
                    case ets:next(?TOPIC_BINDING_PROJECTION, Key2) of
                        {NodeId, _, _} ->
                            collect_select(NodeId, BKeys, Acc);   %% fanout > 2，切换 select
                        _ ->
                            Acc1 = collect_binding(Dest1, BKey1, BKeys, Acc),
                            collect_binding(Dest2, BKey2, BKeys, Acc1)
                    end;
                _ ->
                    collect_binding(Dest1, BKey1, BKeys, Acc)
            end;
        _ -> Acc
    end.
```

`ets:next/2` 在 CATree（ETS ordered_set 的底层数据结构）里每次都是 O(log N) 的栈重建，但对于 1-2 个元素，两次 `ets:next` 比 `ets:select` 快，因为 `ets:select` 有编译 match spec 的固定开销。超过 2 个时，`ets:select` 做一次 O(log N) seek 加 O(F) 扫描，比 F 次各 O(log N) 的 `ets:next` 便宜。

### 2.5 Binding 写入：projection 函数与 Trie 构建

binding 写入不经过 `rabbit_exchange_type_topic` 的 `add_binding` 回调（该回调是 `ok`，什么都不做），而是走 Khepri projection 机制：当 Khepri 存储里的 binding 路径发生变化时，自动触发注册的 projection 函数更新 ETS 表。

projection 函数在 `rabbit_khepri.erl` 里定义：

```erlang
PFun = fun(Tables, Path, OldProps, NewProps) ->
    #{rabbit_khepri_topic_trie_v4   := TrieTab,
      rabbit_khepri_topic_binding_v4 := BindingTab} = Tables,
    {VHost, ExchangeName, Kind, DstName, BindingKey} =
        rabbit_db_binding:khepri_route_path_to_args(Path),
    XSrc = {VHost, ExchangeName},
    Dest = rabbit_misc:r(VHost, Kind, DstName),
    Words = rabbit_db_topic_exchange:split_topic_key_binary(BindingKey),
    case {OldProps, NewProps} of
        {_, #{data := _}} ->
            %% 新增 binding：沿路径创建节点，在叶子插入 binding
            LeafNodeId = trie_follow_down_create(TrieTab, XSrc, Words),
            ets:insert(BindingTab, {{LeafNodeId, BindingKey, Dest}});
        {#{data := _}, _} ->
            %% 删除 binding：找路径、删叶子、GC 空节点
            case trie_follow_down_get_path(TrieTab, XSrc, Words) of
                {ok, LeafNodeId, TriePath} ->
                    ets:delete(BindingTab, {LeafNodeId, BindingKey, Dest}),
                    trie_gc_path(TrieTab, BindingTab, TriePath);
                error -> ok
            end;
        {_, _} -> ok
    end
end,
```

`trie_follow_down_create` 沿词路径逐节点向下，不存在的节点用 `make_ref()` 创建新 ID，并用 `ets:update_counter` 递增父节点的 `ChildCount`。

### 2.6 Trie GC：ChildCount 的作用

删除 binding 时，如果某个内部节点的所有出边都没了，且叶子 binding 表里也没有该节点的记录，这个节点就成了死节点，需要删除以节省内存。`trie_gc_path` 从叶子往根逐节点检查：

```erlang
trie_gc_path(TrieTab, BindingTab, [{Key, ParentEdgeKey, ChildId} | Rest]) ->
    case trie_node_is_empty(TrieTab, BindingTab, Key, ChildId) of
        true ->
            ets:delete(TrieTab, Key),
            _ = case ParentEdgeKey of
                    none -> ok;
                    _    -> ets:update_counter(TrieTab, ParentEdgeKey, {3, -1})  %% ChildCount--
                end,
            trie_gc_path(TrieTab, BindingTab, Rest);
        false -> ok   %% 遇到非空节点，停止（更上层一定也非空）
    end.

trie_node_is_empty(TrieTab, BindingTab, Key, ChildId) ->
    case ets:lookup_element(TrieTab, Key, 3, 0) of
        0 -> not trie_node_has_bindings(BindingTab, ChildId);  %% 无出边且无 binding
        _ -> false
    end.
```

`ChildCount` 就是为这里的快速判断准备的：O(1) 查是否有出边，不需要扫描整张表。

### 2.7 为什么 topic exchange 的 add_binding 是空实现

这是一个初看困惑的设计。`rabbit_exchange_type_topic.erl` 的 `add_binding/3` 回调什么都不做：

```erlang
add_binding(_Serial, _Exchange, _Binding) -> ok.
```

因为 binding 数据的真正存储是在 Khepri 里，Trie 的 ETS 维护全部交给 Khepri projection 自动完成。exchange type 回调是为 Mnesia 遗留路径准备的，在 Khepri 模式下 Trie 的维护不依赖这个回调。这意味着如果你在源码里 grep `add_binding` 想找到 Trie 是哪里写入的，会找不到——它藏在 Khepri projection 的注册逻辑里。

同样地，`route/2` 实际上只调用 `route/3`（传入空 `Opts`），`route/3` 则委托给 `rabbit_db_topic_exchange:match/3`：

```erlang
%% rabbit_exchange_type_topic.erl
route(Exchange, Msg) ->
    route(Exchange, Msg, #{}).

route(#exchange{name = XName}, Msg, Opts) ->
    RKeys = mc:routing_keys(Msg),
    lists:append([rabbit_db_topic_exchange:match(XName, RKey, Opts) || RKey <- RKeys]).
```

一条消息可能携带多个 routing key（AMQP 1.0 扩展场景），因此对每个 RKey 调用 `match` 并 `lists:append` 合并。这里没有去重，调用方负责处理重复目标。

### 2.8 split_topic_key_binary：为什么用 persistent_term 缓存编译后的 Pattern

routing key 是按 `"."` 分词的，代码特别用 `binary:compile_pattern/1` 预编译分隔符模式，然后存入 `persistent_term`：

```erlang
%% rabbit_db_topic_exchange.erl
-define(COMPILED_TOPIC_SPLIT_PATTERN, cp_dot).

split_topic_key_binary(<<>>) ->
    [];
split_topic_key_binary(RoutingKey) ->
    Pattern =
    case persistent_term:get(?COMPILED_TOPIC_SPLIT_PATTERN, undefined) of
        undefined ->
            P = binary:compile_pattern(<<".">>),
            persistent_term:put(?COMPILED_TOPIC_SPLIT_PATTERN, P),
            P;
        P ->
            P
    end,
    binary:split(RoutingKey, Pattern, [global]).
```

`persistent_term` 是 OTP 21 引入的全局只读存储，读取无锁、无进程间通信，比 `ets:lookup` 快约 10 倍。`binary:compile_pattern/1` 返回的是一个 C 级别的编译后搜索对象（对于单个字节来说本质是一个 Boyer-Moore 跳表），每次路由时重用而不是每次编译，在高 QPS 场景下节省了可观的 CPU。

空 routing key `<<>>` 被特殊处理返回 `[]`——一个空词列表——在 Trie 里对应 root 节点直接查 binding，不走 `trie_match` 的词遍历逻辑。

### 2.9 V3/V4 feature flag 切换与 badarg 防护

`match/3` 的实现处理了 V3/V4 两套逻辑的平滑切换：

```erlang
match(#resource{virtual_host = VHost, name = XName} = X, RoutingKey, Opts) ->
    BKeys = maps:get(return_binding_keys, Opts, false),
    Words = split_topic_key_binary(RoutingKey),
    case rabbit_khepri:get_effective_topic_binding_projection_version() of
        V when V >= 4 ->
            try
                trie_match({VHost, XName}, root, Words, BKeys, [])
            catch
                error:badarg ->
                    []
            end;
        _ ->
            trie_match_v3(X, Words, BKeys)
    end.
```

`try/catch error:badarg` 是一个防御性设计。正常情况下 `ets:lookup_element/4` 和 `ets:next/2` 不会抛 `badarg`，但在极短的不一致窗口期（ETS 表刚被删除、或 projection 还未完成初始化时），操作不存在的表会抛 `badarg`。捕获后返回空列表比崩溃更安全——最坏结果是这条消息路由到 0 个队列，客户端收不到 confirm，可以重发。

V3 实现用的是 `#trie_edge{}` record 和单张 `ordered_set` 表（`rabbit_khepri_topic_trie_v3`），V4 才分裂为边表 + 叶子表双结构。V3 代码在 feature flag `topic_binding_projection_v4` 成为 required（即下一个 LTS 升级）前不会删除，注释里明确写了这个计划。

### 2.10 collect_select 的 match spec 结构

当 fanout > 2 时，`collect_select` 切换到 `ets:select`，其 match spec 值得仔细读：

```erlang
%% 不需要返回 binding key 时，只取 Dest（第三个元素）
collect_select(NodeId, false, Acc) ->
    Dests = ets:select(?TOPIC_BINDING_PROJECTION,
                       [{{{NodeId, '_', '$1'}}, [], ['$1']}]),
    Dests ++ Acc;

%% 需要返回 binding key 时，取 {Dest, BKey}
collect_select(NodeId, true, Acc) ->
    DestsAndBKeys = ets:select(?TOPIC_BINDING_PROJECTION,
                               [{{{NodeId, '$1', '$2'}}, [], [{{'$2', '$1'}}]}]),
    format_dest_bkeys(DestsAndBKeys, Acc).
```

`ordered_set` 的 key 是 `{NodeId, BindingKey, Dest}`，match spec 里用 `{NodeId, '_'/'$1', '$1'/'$2'}` 做部分绑定前缀扫描——`ets:select` 在 CATree 底层能优化为从 `{NodeId, <<>>, {}}` 开始的范围扫描，而不是全表扫描，复杂度是 O(log N + F)，N 是叶子 binding 表总条数，F 是该节点的 fanout。

`format_dest_bkeys` 对 `#resource{kind = queue}` 的目标加上 binding key（用于 consumer 侧的消息路由追踪），其他类型（exchange-to-exchange binding）只返回目标，不附带 key，与 `collect_binding` 的逻辑对称。

---

## 第三章：VQ 存储——publish1 的存储决策与 confirm 分流

### 3.1 两类存储、三个位置

`rabbit_variable_queue`（VQ）管理经典队列的消息存储，消息始终处于三个位置之一：

- `memory`：消息体在进程堆，最热，读写零 I/O
- `{rabbit_classic_queue_store_v2, SegmentId, Offset}`（`?IN_QUEUE_STORE`）：per-queue 文件，小消息专用，无需跨进程通信，写入时直接调函数
- `rabbit_msg_store`（`?IN_SHARED_STORE`）：per-vhost 共享进程，大消息专用，多队列间去重，支持 GC 压缩

判断一条消息去哪里，在 `msg_status` 创建时就已决定，通过 `determine_persist_to/2`：

```erlang
determine_persist_to(Msg, IndexMaxSize) ->
    {MetaSize, BodySize} = mc:size(Msg),
    case MetaSize + BodySize >= IndexMaxSize of
        true  -> msg_store;    %% 大消息（默认阈值 4096 字节）→ 共享 msg_store
        false -> queue_store   %% 小消息 → per-queue store
    end.
```

`IndexMaxSize`（配置项 `queue_index_embed_msgs_below`，默认 4096）是分水岭。小于此值的消息写 per-queue store，这样单个队列的消息 I/O 完全隔离，不同队列间不会互相干扰；大消息写 shared msg_store，跨队列共享存储，支持消息内容去重（同一消息投递给多个队列时只存一份内容）。

### 3.2 publish1：内存还是磁盘，由出队速率决定

`publish1` 是 VQ 入队的核心，它根据当前消费速率动态决定新消息放内存还是立即写磁盘：

```erlang
publish1(Msg, MsgProps, IsDelivered, _ChPid, PersistFun,
         State = #vqstate{q_head = QHead,
                          q_tail = QTail = #q_tail{count = QTailCount},
                          qi_embed_msgs_below = IndexMaxSize,
                          next_seq_id = SeqId,
                          rates = #rates{out = OutRate}}) ->
    ...
    %% 动态内存上限：消费速率越快，允许留在内存的消息越多（最多 2048）
    MemoryLimit = min(1 + floor(2 * OutRate), 2048),
    QHeadLen = ?QUEUE:len(QHead),
    State3 = case QTailCount of
        0 when QHeadLen < MemoryLimit ->
            %% q_tail 为空（无磁盘积压）且头部未满 → 不强制落盘
            {MsgStatus1, State1} = PersistFun(false, false, MsgStatus, State),
            State2 = State1#vqstate{q_head = ?QUEUE:in(m(MsgStatus1), QHead)},
            stats_published_memory(MsgStatus1, State2);
        _ ->
            %% q_tail 有内容（磁盘积压存在）或头部已满 → 强制落盘
            {MsgStatus1, State1} = PersistFun(true, true, MsgStatus, State),
            QTail1 = expand_q_tail(SeqId, QTail),
            State2 = State1#vqstate{q_tail = QTail1},
            stats_published_disk(MsgStatus1, State2)
    end,
```

这里的 `MemoryLimit = min(1 + floor(2 * OutRate), 2048)` 是一个精妙的自适应算法：消费速率（`OutRate`，每秒消费的消息数）越高，允许在内存缓存的消息越多，因为它们很快就会被取走，没必要写盘；消费速率接近 0 时，`MemoryLimit = 1`（至少留 1 条，用于判断过期），几乎所有新消息立即落盘。

判断是否立即落盘的条件是 `QTailCount`。`q_tail` 是"磁盘尾部"：一旦队列里有消息已经被写入磁盘（`q_tail.count > 0`），所有新消息必须也写磁盘，否则顺序会乱——后来的内存消息会在磁盘消息之前被消费。

### 3.3 maybe_write_msg_to_disk：真正的写入分流

`PersistFun` 就是 `maybe_write_to_disk/4`，它串联两步：

```erlang
maybe_write_to_disk(ForceMsg, ForceIndex, MsgStatus, State) ->
    {MsgStatus1, State1} = maybe_write_msg_to_disk(ForceMsg, MsgStatus, State),
    maybe_write_index_to_disk(ForceIndex, MsgStatus1, State1).
```

`maybe_write_msg_to_disk` 根据 `Force` 和 `IsPersistent` 决定是否写消息体，写时根据 `persist_to` 字段分流：

```erlang
maybe_write_msg_to_disk(Force, MsgStatus = #msg_status{
                                 msg_location = ?IN_MEMORY,
                                 is_persistent = IsPersistent, ...},
                        State) when Force orelse IsPersistent ->
    case persist_to(MsgStatus) of
        msg_store   ->
            ok = msg_store_write(MSCState, IsPersistent, SeqId, MsgId,
                                 prepare_to_store(Msg)),
            {MsgStatus#msg_status{msg_location = ?IN_SHARED_STORE}, ...};
        queue_store ->
            {MsgLocation, StoreState} =
                rabbit_classic_queue_store_v2:write(SeqId, prepare_to_store(Msg),
                                                    Props, StoreState0),
            {MsgStatus#msg_status{msg_location = MsgLocation}, ...}
    end;
maybe_write_msg_to_disk(_Force, MsgStatus, State) ->
    {MsgStatus, State}.    %% 不写，消息留在 memory
```

**三种情形对应的行为**：

1. `Force=false, IsPersistent=false`：临时消息且不强制 → `msg_location` 保持 `memory`，什么都不写
2. `Force=false, IsPersistent=true`：持久消息但不强制 → 写入对应的 store（`persist_to` 决定去哪里），`msg_location` 更新
3. `Force=true`：无论是否持久 → 强制写入

对于非持久消息：`maybe_write_msg_to_disk(_Force, MsgStatus, State)` 的第三个子句用了 `_Force` 匹配——**即使 `Force=true`，临时消息也不写磁盘**。这是有意为之：临时消息在 Broker 重启后本来就不恢复，强制写盘只是浪费 I/O。

### 3.4 confirm 的双路径：queue_store vs msg_store

`publish1` 入队完成后处理 confirm 注册：

```erlang
{UC1, UCS1} = maybe_needs_confirming(NeedsConfirming, persist_to(MsgStatus),
                                     MsgId, UC, UCS),
```

```erlang
maybe_needs_confirming(false, _, _, UC, UCS) ->
    {UC, UCS};                               %% 不需要 confirm，跳过
maybe_needs_confirming(true, queue_store, MsgId, UC, UCS) ->
    {UC, sets:add_element(MsgId, UCS)};      %% 小消息 → 走简单 confirm 集合 UCS
maybe_needs_confirming(true, _, MsgId, UC, UCS) ->
    {sets:add_element(MsgId, UC), UCS}.      %% 大消息 → 走复杂 confirm 集合 UC
```

这两条路径的区别在于**何时触发 confirm**：

- `unconfirmed_simple`（`UCS`）：per-queue store 写入是同步的，消息写完即落盘，`sync` 时可以直接把 `UCS` 里的全部 MsgId 移入 `confirmed`，不需要等待跨进程回调
- `unconfirmed`（`UC`）：shared msg_store 是异步写入（`write_flow` 投递给 msg_store 进程），需要等待 msg_store 的回调（`msg_on_disk_fun`）通知 VQ 消息已落盘，才能将 MsgId 从 `UC` 移入 `confirmed`

这个设计避免了 per-queue store 也走异步回调的开销——小消息量大、频繁，同步路径吞吐更高。

### 3.5 为什么 q_head 和 q_tail 要分离

`q_head` 是一个 Erlang 队列（`?QUEUE`，底层是两个列表实现的 deque），保存消息的 `#msg_status`（消息体可能在内存也可能在 store）。`q_tail` 只是一个紧凑记录 `#q_tail{start_seq_id, count, end_seq_id}`，不保存消息元数据——因为 `q_tail` 里的消息全在磁盘，只需记录范围，消费时按 seq_id 顺序从 index 读取元数据，再从 store 读消息体。

这个分离使得"热队列"（消息生产后很快消费，`q_tail` 始终为 0）可以完全在内存里运行，没有任何磁盘 I/O；而"冷队列"（消息积压、消费慢）自然地把绝大多数消息放在磁盘上，`q_head` 只保留少量热消息用于快速出队。

### 3.6 vqstate 核心字段一览

`#vqstate` record 有几十个字段，理解其中几个关键分组对读懂整个 VQ 至关重要：

**消息位置三态**：每条消息的 `msg_location` 字段是三选一——`memory`（消息体在进程堆）、`rabbit_msg_store`（大消息，共享 store）、`{rabbit_classic_queue_store_v2, SegmentId, Offset}`（小消息，per-queue store）。宏定义 `?IN_MEMORY`、`?IN_SHARED_STORE`、`?IN_QUEUE_STORE` 在代码里大量出现做模式匹配。

**confirm 双路径**：`unconfirmed`（sets）对应走共享 msg_store 的消息，需要等 msg_store 异步回调；`unconfirmed_simple`（sets）对应走 per-queue store 的消息，sync 时直接移入 `confirmed`。`confirmed` 字段是已可以回复 Publisher Confirm 的 MsgId 集合，`drain_confirmed/1` 把它转为列表返回给上层：

```erlang
%% rabbit_variable_queue.erl
drain_confirmed(State = #vqstate { confirmed = C }) ->
    case sets:is_empty(C) of
        true  -> {[], State}; %% common case
        false -> {sets:to_list(C), State #vqstate {
                                        confirmed = sets:new([{version, 2}]) }}
    end.
```

注意 `{version, 2}` 参数——这是 OTP 25 引入的新版 sets 实现，底层改用 map 而非有序列表，`add_element` 和 `is_element` 都是 O(1) 均摊，相比老版本 O(log N) 有显著提升。

**速率统计**：`out_counter`/`in_counter` 每条消息递增，累积到 100 条（`?MSGS_PER_RATE_CALC`）后调用 `update_rates/1` 用指数移动平均（half-life 5 秒）更新 `#rates{in, out}`，供 `publish1` 里的 `OutRate` 使用。

### 3.7 maybe_write_index_to_disk：index 的条件写入

消息体写入 store 后，还需要决定是否把消息元数据写入队列 index：

```erlang
%% rabbit_variable_queue.erl
maybe_write_index_to_disk(_Force, MsgStatus = #msg_status {
                                    index_on_disk = true }, State) ->
    {MsgStatus, State};   %% 已在磁盘，跳过
maybe_write_index_to_disk(Force, MsgStatus = #msg_status {
                                   msg_id        = MsgId,
                                   seq_id        = SeqId,
                                   is_persistent = IsPersistent,
                                   msg_location  = MsgLocation,
                                   msg_props     = MsgProps},
                          State = #vqstate{index_state = IndexState})
  when Force orelse IsPersistent ->
    IndexState2 = rabbit_classic_queue_index_v2:publish(
                    MsgId, SeqId, MsgLocation, MsgProps, IsPersistent,
                    persist_to(MsgStatus) =:= msg_store,
                    IndexState),
    {MsgStatus#msg_status{index_on_disk = true},
     State#vqstate{index_state = IndexState2}};
maybe_write_index_to_disk(_Force, MsgStatus, State) ->
    {MsgStatus, State}.
```

`index_on_disk = true` 的判断在最顶部——这是一个防重复写的守门条件，当消息因为积压被多次经过此路径时，不会重复写 index。队列 index 记录的信息：MsgId、SeqId、MsgLocation（用于从正确的 store 读消息体）、MsgProps（包含过期时间）、是否持久化。`persist_to(MsgStatus) =:= msg_store` 这个布尔值告诉 index：消息体在共享 store 里（需要 MsgId 查找），还是在 per-queue store 里（按 SeqId + Location 直接读）。

### 3.8 sync 路径：unconfirmed_simple 的批量 confirm

`sync/1` 是 per-queue store 的 confirm 提交点，在 `needs_timeout` 检查后由 `timeout/1` → `sync/1` 触发：

```erlang
sync(State = #vqstate { index_state = IndexState0,
                        store_state = StoreState0,
                        unconfirmed_simple = UCS,
                        confirmed   = C }) ->
    {MsgIdSet, IndexState} = rabbit_classic_queue_index_v2:sync(IndexState0),
    StoreState = rabbit_classic_queue_store_v2:sync(StoreState0),
    State1 = State #vqstate { index_state = IndexState,
                              store_state = StoreState,
                              unconfirmed_simple = sets:new([{version,2}]),
                              confirmed   = sets:union(C, UCS) },
    index_synced(MsgIdSet, State1).
```

这里一次性把 `unconfirmed_simple`（per-queue store 路径的消息）全部移入 `confirmed`。为什么可以一次性全部移？因为 per-queue store 的 `sync` 是**同步** fsync——调用返回时所有已写入的消息都已落盘，没有任何消息"写了但没 fsync"。对比共享 msg_store 路径：msg_store 的写入是异步的（通过 `write_flow` 投递消息给 msg_store 进程），确认需要等 `msg_on_disk_fun` 回调，由 `index_synced` 处理。

`needs_timeout` 的逻辑直接影响 sync 频率：

```erlang
needs_timeout(#vqstate { index_state = IndexState,
                         unconfirmed_simple = UCS }) ->
    case {rabbit_classic_queue_index_v2:needs_sync(IndexState), sets:is_empty(UCS)} of
        {false, false} -> timed;
        {confirms, _}  -> timed;
        {false, true}  -> false
    end.
```

只要 index 需要 sync 或者 `UCS` 非空（有待 confirm 的 per-queue store 消息），就返回 `timed`，触发 gen_server 的 `handle_info(timeout, ...)`，调用 `sync`。

### 3.9 ack 的双路径清理：pending_ack 分离的意义

消息被消费后进入 pending_ack 状态（等待客户端 ack），VQ 用两个 Map 维护：

```erlang
%% #vqstate 字段
ram_pending_ack  :: map(),   %% msg_location 在内存或 queue_store 的待 ack 消息
disk_pending_ack :: map(),   %% msg_location 在 msg_store 的待 ack 消息
```

当消息被 ack 时，`remove_pending_ack` 先查 `ram_pending_ack`，找不到再查 `disk_pending_ack`。这个分离的用意是：msg_store 的消息在 ack 时需要通知 msg_store 进程（调用 `msg_store_remove`），而 per-queue store 的消息直接在本地删除即可，不需要跨进程通信。分离两个 Map 使得 ack 时的路径选择更清晰，不需要每次都检查 `msg_location` 字段。

### 3.5 q_head / q_tail：现代内存管理模型

RabbitMQ 3.12 对 `rabbit_variable_queue` 做了一次较大重构，用更简洁的 **q_head + q_tail 二分模型**替代了旧版的 alpha/beta/gamma/delta 四状态模型。理解这两个模型对于回答面试中"消息堆积时内存怎么变化"以及"为什么内存会报警"至关重要。

#### 3.5.1 历史：alpha/beta/gamma/delta 四状态

旧版（3.12 之前）VQ 用四个状态描述消息的位置：

`alpha`：消息元数据和消息体**都在内存**。这是最热的状态，读写无 I/O，是正常消费路径下消息存在的形式。

`beta`：消息元数据（`#msg_status{}` 结构体）在内存，消息体已写入磁盘（msg_store 或 queue_index）。消息处于"半内存"状态，投递时需要读盘取出消息体。

`gamma`：消息体和队列索引（queue_index）**都在磁盘**，但内存中仍保留元数据。是 beta 的进一步降级。

`delta`：**完全在磁盘**，内存里只保留队列长度和分段范围（`start_seq_id` / `end_seq_id`），连元数据都不在内存。是最省内存的状态，但读取代价最高——每次消费都要先从磁盘加载元数据，再加载消息体。

状态转换方向是单向降级：`alpha → beta → gamma → delta`（paging，内存压力升高时触发），以及单向升级：`delta → gamma → beta → alpha`（prefetching，消费者接近队头时提前加载）。

#### 3.5.2 现代：q_head + q_tail 二分模型

3.12 的重构把四个状态简化为两个结构：

```erlang
%% #vqstate 字段
q_head :: lqueue(),   %% 内存队列：存放 #msg_status{} 列表，消息体可能在内存也可能在磁盘
q_tail :: #q_tail{},  %% 磁盘尾部：只记录 {start_seq_id, count, end_seq_id}，不保留任何元数据
```

`q_head` 是一个 lqueue（基于双端列表的 FIFO），存放最近的 1 到 2048 条消息的 `#msg_status{}` 结构体。每条 `#msg_status{}` 里的 `msg_location` 字段指示消息体在哪里（`memory` / `?IN_QUEUE_STORE` / `?IN_SHARED_STORE`），元数据始终在内存中。

`q_tail` 只是一个计数器结构，记录磁盘上还有多少条消息以及它们的 SeqId 范围，**不在内存里保存任何消息内容或元数据**。这是旧版 delta 状态的对应物，但更彻底——连元数据都不缓存。

内存限制由消费速率动态计算：

```erlang
%% publish1 和 read_from_q_tail 中的内存上限计算
MemoryLimit = min(1 + floor(2 * OutRate), 2048)
%% OutRate：当前出队速率（消息/秒），速率越高，允许缓存在 q_head 的消息越多
%% 最少保留 1 条（用于检查 TTL 过期），最多 2048 条
```

消费越快，队头预加载的消息越多（上限 2048）；队列空置时只保留 1 条在内存。这个自适应机制比旧版的固定状态边界更细腻。

#### 3.5.3 消息写入时的路径选择（publish1 核心逻辑）

```erlang
publish1(..., State = #vqstate{q_head = QHead,
                               q_tail = QTail = #q_tail{count = QTailCount},
                               rates  = #rates{out = OutRate}}) ->
    MemoryLimit = min(1 + floor(2 * OutRate), 2048),
    QHeadLen = ?QUEUE:len(QHead),
    case QTailCount of
        0 when QHeadLen < MemoryLimit ->
            %% q_tail 为空（磁盘无积压）且 q_head 未满
            %% → 消息进 q_head，走内存路径
            %% PersistFun(ForceMsg=false, ForceIndex=false, ...)
            %% → 持久化消息写磁盘，但元数据留在 q_head 内存中
            {MsgStatus1, State1} = PersistFun(false, false, MsgStatus, State),
            State2 = State1#vqstate{q_head = ?QUEUE:in(MsgStatus1, QHead)};
        _ ->
            %% q_tail 不为空（磁盘有积压）或 q_head 已满
            %% → 消息直接写磁盘，只在 q_tail 计数，不进 q_head
            %% PersistFun(ForceMsg=true, ForceIndex=true, ...)
            {MsgStatus1, State1} = PersistFun(true, true, MsgStatus, State),
            QTail1 = expand_q_tail(SeqId, QTail),
            State2 = State1#vqstate{q_tail = QTail1}
    end.
```

这里有一个关键判断：只要 `q_tail` 里有任何磁盘积压（`QTailCount > 0`），新消息就**不会**进 `q_head`，而是直接写磁盘追加到 `q_tail`。这防止了消息乱序——如果磁盘上还有旧消息没消费，新消息先进内存会导致旧消息反而排在新消息后面被消费。

#### 3.5.4 从 q_tail 向 q_head 加载：read_from_q_tail

当 `q_head` 为空但 `q_tail` 不为空时，`fetch_from_q_head` 触发 `read_from_q_tail` 从磁盘批量加载：

```erlang
read_from_q_tail(State = #vqstate{rates = #rates{out = OutRate}}) ->
    MemoryLimit = min(1 + floor(2 * OutRate), 2048),
    %% 从 queue_index 读取 [start_seq_id, start+MemoryLimit) 范围的索引记录
    QTailSeqLimit = QTailSeqId + MemoryLimit,
    QTailSeqId1 = rabbit_classic_queue_index_v2:tune_read(QTailSeqId,
                      min(QTailSeqLimit, QTailSeqIdEnd)),
    {List, IndexState1} = rabbit_classic_queue_index_v2:read(
                              QTailSeqId, QTailSeqId1, IndexState),
    %% 根据消息大小决定是否批量预读消息体
    %% 小消息（per-queue store）：批量读入内存
    %% 大消息（shared msg_store）：超过阈值时才批量读，否则按需单条读
    ...
```

`read_from_q_tail` 一次最多加载 `MemoryLimit` 条消息的**元数据**（`#msg_status{}` 结构体）到 `q_head`，消息体是否一并读入取决于消息大小：小消息（per-queue store）会批量读入，大消息（shared msg_store）只有在消息数量达到 `?SHARED_READ_MANY_COUNT_THRESHOLD`（10 条）且每条大小低于 `?SHARED_READ_MANY_SIZE_THRESHOLD`（12000 字节）时才批量读，否则在实际投递时按需单条读。

#### 3.5.5 内存报警与 paging 触发机制

q_head / q_tail 描述的是**单个队列进程**的内存管理，但 RabbitMQ 还有一层**全局内存压力**机制，负责在整个节点内存不足时强制把队列数据 page 到磁盘。两者协同工作，共同保证节点不会 OOM。

**vm_memory_monitor 的角色**

`vm_memory_monitor` 是一个 gen_server，每秒（默认）采样 Erlang VM 的内存占用（通过 `erlang:memory(total)` 或读取 `/proc/meminfo`）。当可用内存占比超过 `vm_memory_high_watermark`（默认 0.4，即物理内存的 40%）时，它向 `rabbit_alarm` 发布一条 `memory` 级别的 alarm：

```erlang
%% vm_memory_monitor.erl（简化）
case MemUsed / TotalMem > WaterMark of
    true  -> rabbit_alarm:set_alarm({{resource_limit, memory, node()}, []});
    false -> rabbit_alarm:clear_alarm({resource_limit, memory, node()})
end
```

**两级阈值：paging 先于 blocking**

RabbitMQ 对内存压力的响应分两级：

第一级是 `vm_memory_high_watermark_paging_ratio`（默认 0.5）。当内存占用达到 watermark 的 50% 时（即物理内存的 20%），触发 **paging**——通知所有经典队列进程开始把 `q_head` 中的消息元数据写到磁盘（降级到 q_tail），释放内存，但**不阻断发布者**。

第二级是 `vm_memory_high_watermark`（默认 0.4，但注意这里的 0.4 指的是占总物理内存 40%）。当内存占用超过这个阈值时，触发 **blocking**——所有 Channel 收到 `channel.flow{active=false}`，发布者被暂停。如果内存继续增长，连接可能被强制关闭。

**paging 的执行路径**

paging 指令通过 `rabbit_memory_monitor` 下发给各队列进程。队列进程 `rabbit_amqqueue_process` 收到 `{run_backing_queue, rabbit_variable_queue, Fun}` 消息后，在 `run_backing_queue_async` 中调用 `Fun(State)` 来执行 paging：

```erlang
%% rabbit_variable_queue.erl：paging 时调用的函数（简化）
paged_out(#vqstate{q_head = QHead} = State) ->
    %% 把 q_head 中所有消息元数据对应的消息体强制写磁盘
    {NewQHead, State1} = lqueue:foldl(
        fun(MsgStatus, {Acc, S0}) ->
            {MsgStatus1, S1} = maybe_write_msg_to_disk(true, MsgStatus, S0),
            {lqueue:in(MsgStatus1, Acc), S1}
        end, {lqueue:new(), State}, QHead),
    %% 如果 q_head 中消息数量超过保留上限，把多余的降级到 q_tail
    drop_excess_from_q_head(State1#vqstate{q_head = NewQHead}).
```

执行完毕后，`q_head` 只保留不超过 `MemoryLimit` 条元数据（通常在 paging 压力下上限很低），其余全部移入 `q_tail`，消息体已写到磁盘，内存大幅释放。

**磁盘报警（disk_free_alarm）**

与内存报警对称，`rabbit_disk_monitor` 监控磁盘空闲空间。当磁盘空闲量低于 `disk_free_limit`（默认 50 MB 或内存大小，取较大值）时，触发 `disk` alarm，同样通过 `rabbit_alarm` 广播，所有发布者被阻断，防止写磁盘失败导致数据丢失。

#### 3.5.6 面试要点直答

**"内存报警了怎么办？"**

首先区分是临时堆积还是持续增长。临时堆积通常是消费者处理速度跟不上，可以临时增加消费者实例，或者检查消费者处理逻辑是否有性能瓶颈。如果是持续增长，说明生产速率长期高于消费速率，需要从架构层面解决——加消费者、降低发布速率、或者开启消息 TTL 和死信队列来限制堆积上限。运维上可以适当调大 `vm_memory_high_watermark`，但这只是缓兵之计，根本还是消费能力。

**"消息为什么会被 page 到磁盘？"**

有两种触发路径。一是**主动 paging**：节点内存占用达到 `vm_memory_high_watermark_paging_ratio` 阈值，`rabbit_memory_monitor` 下发指令，队列进程主动把 q_head 降级到 q_tail，消息体写入磁盘，释放内存。二是**被动写盘**：单队列自身的 q_head 超过 `MemoryLimit`（`1 + floor(2 * OutRate)`，最大 2048），新消息直接绕过 q_head 进入 q_tail，即在进入队列的瞬间就写到了磁盘。第二种情况在消费者消费速率为 0（即堆积增长阶段）时尤为明显——OutRate=0，MemoryLimit=1，几乎每条新消息都会直接落盘。

**"持久化消息和非持久化消息在内存压力下表现一样吗？"**

不完全一样。持久化消息（`delivery_mode=2`）在 `publish1` 时就会调用 `maybe_write_msg_to_disk` 把消息体写到 msg_store，但元数据（`#msg_status{}`）仍在 q_head 里。内存压力下被 paging 时，持久化消息的消息体已经在磁盘了，只需要把元数据从 q_head 移出即可，代价极低。非持久化消息的消息体在 paging 前都在内存，paging 时需要把消息体写到 per-queue store，I/O 代价更高。所以在消息堆积场景下，持久化消息反而比非持久化消息更"内存友好"。

---

## 第三章补充：磁盘持久化深度解析——从 write 调用到 fsync 落盘的完整路径

前面第三章讲的是"何时决定写磁盘"（`maybe_write_msg_to_disk`、`publish1` 的 ForceMsg 判断）。这里要回答另一个更底层的问题：**调用 write 之后，数据到底经过了哪些层才真正落到磁盘上，fsync 是什么时机触发的，节点崩溃重启后如何恢复。**

RabbitMQ 的磁盘写入涉及两个完全独立的存储模块，路径和机制截然不同，需要分开理解。

### 补3.1 per-queue store（rabbit_classic_queue_store_v2）的文件结构与写入流程

**文件布局**

per-queue store 为每个经典队列维护一组 segment 文件，存放在该队列的数据目录下（`<mnesia_dir>/queues/<queue_name>/`）：

```
queues/<queue_name>/
  ├── 0.idx          # queue_index segment 0（索引文件）
  ├── 0.wal          # queue_index WAL（崩溃恢复用）
  ├── 0.qsx          # queue_store segment 0（消息体）
  ├── 1.qsx          # queue_store segment 1
  └── ...
```

每个 `.qsx` 文件是一个 **segment**，固定存储 65536（`1 bsl 16`）条消息的消息体。文件内部是一条条紧密排列的定长头 + 变长消息体：

```
[4 字节消息长度][消息体字节...][4 字节消息长度][消息体字节...]...
```

写入时，`rabbit_classic_queue_store_v2:write/3` 直接调用 `file:write/2`，把消息体追加到当前 segment 文件的末尾，**不做任何缓冲**——写入的是 Erlang 文件句柄缓冲区（OS page cache），不是 RabbitMQ 自己的 buffer。

**fsync 时机：sync/1**

仅靠 `file:write` 写到 page cache 是不够的，掉电就会丢失。实际落盘（`fsync`）发生在 `rabbit_classic_queue_store_v2:sync/1`，由 VQ 的 `sync/1` 函数统一驱动：

```erlang
%% rabbit_variable_queue.erl
sync(State = #vqstate{index_state  = IndexState0,
                      store_state  = StoreState0,
                      unconfirmed_simple = UCS}) ->
    {MsgIdSet, IndexState} = rabbit_classic_queue_index_v2:sync(IndexState0),
    StoreState = rabbit_classic_queue_store_v2:sync(StoreState0),
    %% sync 完成 → 把 UCS 里所有 MsgId 移入 confirmed
    ...
```

`rabbit_classic_queue_store_v2:sync/1` 内部对当前写入的 segment 文件调用 `file:sync/1`（对应 OS 的 `fsync` 或 `fdatasync` 系统调用），把 page cache 刷入磁盘后返回。由于 per-queue store 的写和 fsync 都在队列进程自己的进程里执行（无需跨进程通信），整个调用是**同步的**——`sync/1` 返回时即表示数据已落盘。

**触发频率：needs_timeout 机制**

`sync/1` 不是每条消息写一次（那样性能会很差），而是由 `needs_timeout/1` 控制的定时批量触发：

```erlang
needs_timeout(#vqstate{index_state = IndexState,
                       unconfirmed_simple = UCS}) ->
    case {rabbit_classic_queue_index_v2:needs_sync(IndexState),
          sets:is_empty(UCS)} of
        {false, _}    -> timed;   %% index 需要 sync
        {_,     false} -> timed;  %% 有未 confirm 的 per-queue store 消息
        _              -> idle
    end.
```

只要有未 confirm 的消息（`UCS` 非空）或 index 有脏数据，`needs_timeout` 返回 `timed`，gen_server 会在下一个 timeout（默认 200ms）触发 `handle_info(timeout, ...)` → `timeout/1` → `sync/1`。因此，持久化消息从写入 page cache 到真正 fsync 落盘，**最大延迟约 200ms**（`rabbit_queue_index_embed_msgs_below` 相关的配置项控制 index 的 sync 阈值，默认不改的话就是 200ms 的 timeout 周期）。

这 200ms 的窗口是 RabbitMQ 吞吐量与持久化延迟之间的权衡：越长，批量 fsync 的 I/O 摊销越好，吞吐越高；越短，Publisher Confirm 的延迟越低，但 I/O 压力越大。

### 补3.2 shared msg_store（rabbit_msg_store）的文件结构与写入流程

**整体设计：两个独立的 msg_store 进程**

`rabbit_msg_store` 是 per-vhost 的共享进程，但实际上每个 vhost 会启动**两个**独立的 msg_store 实例：

- `rabbit_msg_store_persistent`：存持久化消息（`delivery_mode=2`），节点重启后需要恢复
- `rabbit_msg_store_transient`：存临时消息（`delivery_mode=1`）的消息体（当临时消息因堆积被 paged out 写盘时），节点重启后**直接丢弃**，不需要恢复

两者文件目录分别是 `<mnesia_dir>/msg_store_persistent/` 和 `<mnesia_dir>/msg_store_transient/`。

**文件布局：journal + segment**

每个 msg_store 目录结构如下：

```
msg_store_persistent/
  ├── journal.jif         # WAL 日志，顺序追加写，快速恢复用
  ├── <file_id_1>.rdq     # segment 文件（消息体存储）
  ├── <file_id_2>.rdq
  └── ...
```

`.rdq` 文件是 segment 文件，每个文件大小上限由 `msg_store_file_size_limit` 配置（默认 16 MB，实际是 `16#4000000` 即 64 MB——以代码为准）。文件内部格式：

```
[1 字节 tag=1][4 字节消息长度][MsgId（16 字节）][消息体字节...]
[1 字节 tag=0][4 字节 MsgId 长度][MsgId]   ←── 这是 tombstone（删除标记）
```

**journal（.jif）的作用**

journal 文件是 msg_store 的 WAL（Write-Ahead Log）。每次 `write_flow`（写消息）或删除操作，都会**先**追加一条记录到 journal，再写 segment 文件。journal 写入格式极简——只写操作类型 + MsgId + 必要元数据，不写消息体本身（消息体直接写 segment）。

journal 的存在是为了崩溃恢复：节点重启时，msg_store 先加载所有 segment 文件重建内存索引（`ets` 表：`MsgId → {FileId, Offset, Len, RefCount}`），然后重放 journal 中的操作来修正索引，补上崩溃前那批已写但还没被 segment index 反映的操作。

**写入路径：write_flow 的异步性**

队列进程（rabbit_variable_queue）写 msg_store 的入口是 `msg_store_write`，它调用 `rabbit_msg_store:write_flow/4`，本质是向 msg_store 进程**发送一条消息**（`gen_server:cast`），不等待返回：

```erlang
%% rabbit_msg_store.erl（简化）
handle_cast({write, MsgId, Msg, From}, State) ->
    %% 1. 追加到 journal
    append_to_journal(write, MsgId, State),
    %% 2. 写入当前 segment 文件（page cache）
    write_to_segment(MsgId, Msg, State),
    %% 3. 更新内存索引（ETS）
    update_index(MsgId, FileId, Offset, State),
    %% 4. 通知队列进程"消息已在内存索引中，可以投递"（但还没 fsync！）
    send_credit(From, State).
```

注意第 4 步：msg_store 通知队列进程的时机是"消息已写入 page cache + 内存索引已更新"，**此时 fsync 还没发生**。队列进程在这个阶段收到通知后，会把对应 MsgId 从 `unconfirmed` 移入 `confirmed`，等待 `drain_confirmed` 取走回复 Publisher Confirm。

这意味着：**shared msg_store 路径下，Publisher Confirm 发出时数据可能还在 page cache，尚未 fsync**。这是 RabbitMQ 的一个已知设计取舍——完全同步的 fsync 吞吐太低，大消息场景下改为写 page cache 即 confirm，依靠 OS fsync 周期（通常 30s）或 msg_store 的定时 sync 来保证最终落盘。

**msg_store 的 fsync 时机**

msg_store 在两种情况下触发 fsync：

第一种是**被动触发**：`rabbit_classic_queue_index_v2:sync` 完成后，如果检测到 msg_store 有未 sync 的数据（通过 `maybe_sync` 信号），会向 msg_store 进程发送 `sync` 指令，msg_store 对所有打开的 segment 文件和 journal 文件调用 `file:sync/1`。

第二种是**主动的 journal compact**：当 journal 文件积累到一定大小（默认约 100 万条记录），msg_store 会触发一次 journal compact——把 journal 里的操作合并写入 segment 文件、清空 journal、然后对 segment 文件 fsync。compact 完成后，journal 重置为空，segment 文件得到完整落盘保证。

**消息去重（reference counting）**

shared msg_store 的一个重要特性是**消息内容去重**。当同一条消息通过 fanout exchange 路由到多个队列时，msg_store 只存储一份消息体，用引用计数管理生命周期：

```erlang
%% 内存索引（ETS）
{MsgId → {FileId, Offset, Len, RefCount}}
%%                                  ^^^^^
%% RefCount = 几个队列在引用这条消息体
%% 最后一个队列 ack 时 RefCount 降为 0，才真正删除
```

ack 时队列进程调用 `rabbit_msg_store:remove/2`，msg_store 把该 MsgId 的 RefCount 减 1；当 RefCount 降为 0，msg_store 在 segment 文件中写一个 tombstone 标记，并更新内存索引，等待下次 GC 时真正释放空间。

**GC：碎片整理**

segment 文件被大量写入 tombstone 后会产生空洞（逻辑删除但文件空间未回收）。msg_store 有一个后台 GC 机制：当文件有效数据比例低于阈值时，把该文件的存活消息体复制到新文件，然后删除原文件，完成空间回收。GC 发生在 msg_store 进程自身（单线程），不影响正常写入（写入始终追加到最新的 segment 文件）。

### 补3.3 queue_index（rabbit_classic_queue_index_v2）的 WAL 与 sync

消息体之外，每条消息还有一份**索引记录**，存在 queue_index（`.idx` 文件）中，记录该消息的 SeqId、MsgId、存储位置、消息属性（TTL、优先级）、是否持久化。queue_index 是消费端的核心——消费时先读 index 知道消息在哪里，再按位置读消息体。

**WAL（.wal 文件）**

queue_index 也有自己的 WAL，存在 `.wal` 文件中（与 msg_store 的 journal 类似）。每次 `rabbit_classic_queue_index_v2:publish/5` 都会把索引记录追加到 `.wal`，在 `sync` 时把 `.wal` 里的内容刷入 `.idx` segment 文件并调用 fsync，然后清空 `.wal`。

**sync 路径**

`rabbit_classic_queue_index_v2:sync/1` 的执行步骤：
1. 把 `.wal` 中所有脏记录写入对应的 `.idx` segment 文件
2. 对所有修改过的 `.idx` 文件调用 `file:sync/1`
3. 对 `.wal` 文件本身调用 `file:sync/1`（确保 WAL 也落盘，用于崩溃恢复）
4. 返回本次 sync 覆盖的 MsgId 集合（供 VQ 的 `index_synced` 处理 msg_store 路径的 confirm）

### 补3.4 节点崩溃后的恢复路径

节点重启时，每个经典队列的恢复流程如下：

**第一步：msg_store 恢复**

`rabbit_msg_store` 启动时扫描所有 `.rdq` segment 文件，把 `MsgId → {FileId, Offset, Len}` 重建到 ETS 内存索引。然后重放 journal（`.jif`），应用崩溃前那批未合并到 segment 的写入和删除操作，修正内存索引。最后做一次 journal compact，清空 journal。这一步只针对 `msg_store_persistent`，`msg_store_transient` 直接清空目录重建。

**第二步：queue_index 恢复**

`rabbit_classic_queue_index_v2` 重放 `.wal`，把 WAL 中的索引记录应用到 `.idx` 文件，重建 SeqId → 消息位置的映射关系。这一步决定了队列的消息顺序和边界（`next_seq_id`、已 ack 的 gap 等）。

**第三步：VQ 重建 q_head / q_tail**

`rabbit_variable_queue:start_msg_store/2` 综合 queue_index 的恢复结果，重建 `#vqstate`：把 index 里所有未 ack 的持久化消息（SeqId 范围）放入 `q_tail`，设置 `q_head` 为空，等待消费者来触发 `read_from_q_tail` 按需加载。非持久化消息的 index 记录在恢复时会被直接丢弃（它们的消息体在 `msg_store_transient` 里已经删除了）。

**为什么非持久化消息重启后消失**

恢复时 VQ 只加载 `is_persistent = true` 的 index 记录，非持久化的 index 记录直接跳过。与之对应，`msg_store_transient` 目录在重启时整体清空——不恢复、不重放 journal。所以非持久化消息在节点重启后彻底消失，是设计上的预期行为，而非 bug。

### 补3.5 完整的"写入 → fsync → confirm"时序图

以一条持久化消息（`delivery_mode=2`，消息体大于 4096 字节，走 shared msg_store）为例，梳理从 Channel 接收到 Publisher 收到 confirm 的完整时序：

```
Channel（rabbit_channel）
  │
  │  basic.publish（delivery_mode=2，body > 4096B）
  ▼
rabbit_amqqueue_process.deliver_or_enqueue
  │
  │  调用 backing_queue:publish → rabbit_variable_queue:publish1
  ▼
maybe_write_msg_to_disk(Force=true, persist_to=msg_store)
  │
  │  msg_store_write → rabbit_msg_store:write_flow（gen_server cast，不等待）
  │  append_to_journal（.jif）                   ← 立即追加到 journal
  │  write_to_segment（.rdq，写入 page cache）   ← 立即写入 page cache
  │  update_index（ETS：MsgId → {FileId, Offset}）
  │  send_credit（信用流控）
  │
  │  msg_store 回调 msg_on_disk_fun
  │  → VQ 把 MsgId 从 unconfirmed 移入 confirmed
  │
  ▼
maybe_write_index_to_disk（写入 queue_index .wal）
  │
  │  200ms timeout → needs_timeout → sync/1
  │
  ▼
rabbit_classic_queue_index_v2:sync
  │  把 .wal 刷入 .idx，fsync .idx，fsync .wal
  │  返回 MsgIdSet（本次 sync 的消息集合）
  ▼
index_synced(MsgIdSet)
  │  msg_store 此时也完成 sync（.rdq fsync）
  ▼
drain_confirmed → 列表返回给 rabbit_amqqueue_process
  ▼
rabbit_channel:confirm_messages → basic.ack 发给 Publisher
```

关键结论：**Publisher 收到 `basic.ack` 的时刻 = queue_index sync 完成的时刻**（对于 per-queue store 路径，消息体此时也已 fsync；对于 shared msg_store 路径，消息体的 fsync 与 index 的 fsync 可能略有先后，但两者都发生在 confirm 发出之前或同批次 sync 中）。最大 confirm 延迟由 `needs_timeout` 的 200ms 定时器决定，与消息数量无关——即使一秒内发了 10 万条消息，也只会有一次批量 fsync + 一次批量 confirm。

---

## 第四章：消息可靠性——confirm 跨队列聚合的精确语义

### 4.1 confirm 的核心挑战

一条 `basic.publish` 可能因 fanout/topic exchange 路由到多个队列，每个队列何时落盘是独立的、异步的。Channel 需要等所有目标队列都确认，才能向发布方发出 `basic.ack`。这是个典型的"散射-聚合"（scatter-gather）问题，而且在高并发下，一个 Channel 同时有数千条消息处于 unconfirmed 状态。

### 4.2 rabbit_confirms：双层 Map 结构

```erlang
-record(?MODULE, {
    smallest  :: undefined | seq_no(),
    unconfirmed = #{} :: #{seq_no() => {exchange_name(), #{queue_name() => ok}}}
}).
```

外层 Map 的键是序列号（`seq_no`，单调递增的整数），值是 `{XName, QueueSet}`：`XName` 用于 nack 时携带交换机信息；`QueueSet` 是一个以队列名为键、`ok` 为值的内层 Map，表示这条消息还在等哪些队列的确认。

`smallest` 字段维护当前最小的未确认序列号，用于 `basic.ack{multiple=true}` 的批量发送——当 `smallest` 本身被 confirm 时，需要重新扫描找到新的最小值（通过 `smallest(unconfirmed)` 计算）。

### 4.3 insert：防重入的前置守卫

```erlang
%% rabbit_confirms.erl
-spec insert(seq_no(), [queue_name()], exchange_name(), state()) -> state().
insert(SeqNo, QNames, #resource{kind = exchange} = XName,
       #?MODULE{smallest = S0,
                unconfirmed = U0} = State)
  when is_integer(SeqNo)
       andalso is_list(QNames)
       andalso not is_map_key(SeqNo, U0) ->
    U = U0#{SeqNo => {XName, maps:from_keys(QNames, ok)}},
    S = case S0 of
            undefined -> SeqNo;
            _ -> S0
        end,
    State#?MODULE{smallest = S,
                  unconfirmed = U}.
```

几个值得注意的细节：`not is_map_key(SeqNo, U0)` 是 guard 条件，而非 case 分支——如果 SeqNo 已存在（即重复 insert），这个函数直接 crash（函数没有匹配到其他子句时抛 function_clause）。这是有意为之：调用方应保证 SeqNo 单调递增，重复 insert 是调用方 bug，应该尽早崩溃而非静默处理。

`maps:from_keys(QNames, ok)` 是 OTP 24 新增的函数，等价于 `maps:from_list([{Q, ok} || Q <- QNames])`，但更高效，因为不需要构造中间列表。生成的内层 Map 用 `ok` 作为值（实际上不关心值，只需要键的存在性），`is_map_key` 查找比 `lists:member` 快。

`smallest` 的维护策略：只在当前 `smallest` 为 `undefined` 时才初始化为 SeqNo，否则保持不变。因为 SeqNo 单调递增，第一条插入的 SeqNo 永远是最小的，后续插入不需要更新 `smallest`——直到最小序号被 confirm。

### 4.4 confirm_one 的精确去队列逻辑

```erlang
%% rabbit_confirms.erl（内部函数）
confirm_one(SeqNo, QName, Smallest, {Acc, ConfirmedSmallest0, U0}) ->
    case maps:take(SeqNo, U0) of
        {{XName, QS}, U1}
          when is_map_key(QName, QS)
               andalso map_size(QS) == 1 ->
            %% QS 里只剩 QName 这一个队列，消息完全 confirmed
            ConfirmedSmallest = case SeqNo of
                                    Smallest -> true;
                                    _ -> ConfirmedSmallest0
                                end,
            {[{SeqNo, XName} | Acc], ConfirmedSmallest, U1};
        {{XName, QS}, U1} ->
            %% 还有其他队列，从集合里摘掉 QName（QName 可能根本不在 QS 里，maps:remove 幂等）
            {Acc, ConfirmedSmallest0, U1#{SeqNo => {XName, maps:remove(QName, QS)}}};
        error ->
            %% SeqNo 不在 unconfirmed 里（可能已全部确认），忽略
            {Acc, ConfirmedSmallest0, U0}
    end.
```

注意源码中第二个子句没有 `when is_map_key(QName, QS)` 守卫——`maps:remove` 对不存在的键是幂等的，所以"QName 不在 QS"和"QName 在 QS 但还有其他队列"两种情况合并为同一分支，减少了代码分支数。`maps:take/2` 比 `maps:get + maps:remove` 更高效，因为只做一次哈希查找。

### 4.5 next_smallest：线性扫描的设计意图

```erlang
%% rabbit_confirms.erl
next_smallest(_S, U) when map_size(U) == 0 ->
    undefined;
next_smallest(S, U) when is_map_key(S, U) ->
    S;
next_smallest(S, U) ->
    %% TODO: this is potentially infinitely recursive if called incorrectly
    next_smallest(S+1, U).
```

`next_smallest` 从当前最小序号开始，逐一递增检查是否存在于 `unconfirmed` Map 中，找到第一个存在的序号返回。这是 O(gap 大小) 的线性扫描，看起来很朴素，但有其合理性：

序列号是由 AMQP Channel 的 `delivery_tag` 自然生成的，单连接的连续入队通常是密集的（seq 1, 2, 3, 4, ...），没有大空洞。最常见的 confirm 场景是多个队列依次 confirm 同一批消息，gap 通常为 0（最小序号本身就在 Map 里）。只有当消息以非顺序方式被 confirm 时才有 gap，且 gap 不会太大（受 prefetch 限制）。

注释中的 `TODO: this is potentially infinitely recursive` 是个诚实的警告——如果调用方传入一个不在 Map 里且比所有现有键都大的 SeqNo，就会无限递归。但这种情况不会在正确使用下发生，因为 `next_smallest` 只在 `ConfirmedSmallest = true` 时被调用，此时 `Smallest0` 已从 Map 中被删除，新的最小值一定在 `Smallest0 + 1` 的某个位置。

### 4.6 队列消失时的隐式 confirm

当一个目标队列被删除或其节点下线，等待它 confirm 的消息不能永远挂起。`rabbit_confirms:remove_queue/2` 模拟了"该队列已确认所有消息"的效果：

```erlang
remove_queue(QName, #?MODULE{unconfirmed = U} = State) ->
    SeqNos = maps:fold(fun(SeqNo, {_XName, QS}, Acc) ->
        case maps:is_key(QName, QS) of true -> [SeqNo|Acc]; false -> Acc end
    end, [], U),
    confirm(lists:sort(SeqNos), QName, State).
```

这是一次全表扫描，时间复杂度 O(unconfirmed 条数)。在极端情况下（一条连接积压了数万条 unconfirmed 消息，此时某个队列节点崩溃），这个扫描会有显著延迟。实践中 prefetch 和 credit_flow 限制了积压深度，通常不是问题。

### 4.7 reject：nack 的精确删除与 smallest 更新

`reject/2` 处理 nack 场景（`basic.nack` 或路由失败的 mandatory 消息）：

```erlang
%% rabbit_confirms.erl
-spec reject(seq_no(), state()) ->
    {ok, mx(), state()} | {error, not_found}.
reject(SeqNo, #?MODULE{smallest = Smallest0,
                       unconfirmed = U0} = State)
  when is_integer(SeqNo) ->
    case maps:take(SeqNo, U0) of
        {{XName, _QS}, U} ->
            Smallest = case SeqNo of
                           Smallest0 ->
                               next_smallest(Smallest0, U);
                           _ ->
                               Smallest0
                       end,
            {ok, {SeqNo, XName}, State#?MODULE{unconfirmed = U,
                                               smallest = Smallest}};
        error ->
            {error, not_found}
    end.
```

与 `confirm` 不同，`reject` 是**无条件删除**整个 SeqNo 条目（不管还有哪些队列未确认），因为 nack 代表整条消息处理失败，不需要等其他队列。`_QS` 直接被丢弃。

返回值 `{ok, {SeqNo, XName}}` 携带了交换机名称，调用方（Channel）用它来构造 `basic.nack` 帧并发给 Publisher。若 SeqNo 不在 `unconfirmed` 里（`error`），返回 `{error, not_found}`——这在 mandatory 消息的路由失败路径中可能触发：消息还未进入任何队列就已被 nack，`rabbit_confirms` 里还没有这条记录。

`smallest` 的更新逻辑和 `confirm_one` 一致：只有删除的恰好是当前最小序号时，才调用 `next_smallest` 重新扫描；否则直接保留原 `smallest`。

### 4.8 confirm 的发送时机：next_state 的作用

Channel 不会在每条消息确认后立即发 `basic.ack`，而是批量发送。`noreply/1` 内部调用 `next_state/1`，后者调用 `send_confirms_and_nacks/1`：

```erlang
send_confirms_and_nacks(State = #ch{confirmed = Cs, rejected = Rs}) ->
    %% 合并所有已 confirm/reject 的 SeqNo，排序后批量发送
    ok = send_confirms(Cs, State),
    ok = send_rejects(Rs, State),
    State#ch{confirmed = [], rejected = []}.
```

`send_confirms` 遍历 `Cs`（已确认的 `{SeqNo, XName}` 列表），按序聚合：如果多个 SeqNo 连续，发一条 `basic.ack{multiple=true}`；如果中间有 gap，分别发送。这是 gen_server2 每次 callback 结束后自动触发的——不需要定时器，也不需要显式 flush，天然批量。

### 4.9 事务 vs Confirm 的语义差异

AMQP 事务（`tx.select/commit`）和 Publisher Confirm 都保证消息落盘，但语义不同：

- 事务的 `tx.commit` 是**同步等待**：客户端发 commit，Channel 投递消息后等待所有队列的同步 confirm，然后才回复 `tx.commit-ok`。期间 Channel 进程被阻塞，无法处理其他请求。
- Confirm 是**异步回调**：Channel 投递消息后立即处理下一条，confirm 来了更新 `unconfirmed` map，批量回复客户端。

高吞吐场景下，Confirm 的吞吐可以达到事务的数十倍，代价是客户端需要自己管理序列号和重发逻辑。

---

## 第五章：流控与背压——三层限流的协同

### 5.1 三层流控的关系

RabbitMQ 的流控体系是分层的，每一层针对不同的生产速率超载场景：

```
[发布方 TCP 连接]
       │
   [credit_flow]    ← 第一层：Channel 处理速率限制 Reader 读取速率
       │
  [rabbit_channel]
       │ basic.publish
   [队列进程 credit] ← 第二层：队列写入速率限制 Channel 转发速率
       │
  [队列进程 VQ]
       │
  [vm_memory_monitor] ← 第三层：全局内存水位限制所有发布连接
```

每一层独立工作，当下游处理不过来时，背压逐层向上传导，最终在 TCP 层形成流控。

### 5.2 vm_memory_monitor 的三种内存计算策略

`vm_memory_monitor` 是一个 `gen_server`，每 2500ms（默认）检查一次内存，通过 `vm_memory_calculation_strategy` 配置三种策略。策略的选择在 `get_memory_calculation_strategy/0` 中确定：

```erlang
%% vm_memory_monitor.erl
get_memory_calculation_strategy() ->
    case rabbit_misc:get_env(rabbit, vm_memory_calculation_strategy, rss) of
        allocated -> allocated;
        erlang    -> erlang;
        legacy    -> erlang;   %% backwards compatibility
        rss       -> rss;
        UnsupportedValue ->
            ?LOG_WARNING(...),
            rss
    end.
```

`legacy` 是历史遗留别名，映射到 `erlang`。不认识的值自动降级到 `rss`，而不是崩溃——这是内存监控这类"基础设施"组件的防御性设计。

三种策略的实际实现在 `get_process_memory_using_strategy/2`：

```erlang
%% vm_memory_monitor.erl
get_process_memory_using_strategy(rss, #state{os_type = {unix, linux},
                                              page_size = PageSize,
                                              proc_file = ProcFile}) ->
    Data = read_proc_file(ProcFile),
    [_|[RssPagesStr|_]] = string:tokens(Data, " "),
    ProcMem = list_to_integer(RssPagesStr) * PageSize,
    {ok, ProcMem};
get_process_memory_using_strategy(rss, #state{os_type = {unix, _},
                                              os_pid = OsPid}) ->
    Cmd = "ps -p " ++ OsPid ++ " -o rss=",
    CmdOutput = os:cmd(Cmd),
    case re:run(CmdOutput, "[0-9]+", [{capture, first, list}]) of
        {match, [Match]} ->
            ProcMem = list_to_integer(Match) * 1024,
            {ok, ProcMem};
        _ ->
            {error, {unexpected_output_from_command, Cmd, CmdOutput}}
    end;
get_process_memory_using_strategy(rss, _State) ->
    {ok, recon_alloc:memory(allocated)};
get_process_memory_using_strategy(allocated, _State) ->
    {ok, recon_alloc:memory(allocated)};
get_process_memory_using_strategy(erlang, _State) ->
    {ok, erlang:memory(total)}.
```

**rss 策略（Linux）**：读 `/proc/$pid/statm`——这是一个虚拟文件，`file:read_file` 无法使用（虚拟文件大小报告为 0），所以用 `read_proc_file` 以 raw 模式逐块读取直到 EOF。`statm` 的格式是空格分隔的七个字段，第一个是虚拟内存页数，**第二个**（`RssPagesStr`）是 RSS 页数。`page_size` 在进程初始化时通过 `getconf PAGESIZE` 一次性获取并缓存在 `#state{}` 里，避免每次轮询都 fork 子进程。

**rss 策略（其他 Unix）**：执行 `ps -p PID -o rss=`，结果单位是 KB，乘以 1024 转换为字节。注意这比 Linux 的 `/proc` 读取开销大得多（需要 fork + exec）。

**rss 策略（Windows/fallback）**：直接用 `recon_alloc:memory(allocated)`，因为 Windows 没有 `/proc`，而 `allocated` 在 Windows 上是最接近实际占用的指标。

**allocated 策略**：`recon_alloc:memory(allocated)` 返回 Erlang BEAM 的所有内存分配器（`erts_alloc`）已从 OS 申请的总内存，包含 Erlang 内部碎片和空闲块，通常比实际使用高 5-15%，是比 `erlang:memory(total)` 更保守的估计。

**erlang 策略**：`erlang:memory(total)` 是 Erlang VM 的自我报告，只统计正在使用的内存，不含分配器持有但已释放的内存块。文件注释明确说明："erlang:memory(total) under-reports memory usage by around 20%"。

### 5.3 internal_update：轮询心跳与告警状态机

`vm_memory_monitor` 的定时逻辑由 `handle_info(update, State)` 驱动：每次 `update` 消息到来，先取消上一个定时器，执行 `internal_update`，然后重新 `send_after`。这样即使 `internal_update` 本身耗时，两次检查之间的间隔是"至少 timeout"，而不是严格的 timeout——避免了检查堆叠：

```erlang
%% vm_memory_monitor.erl
handle_info(update, State) ->
    _ = erlang:cancel_timer(State#state.timer),
    State1 = internal_update(State),
    TRef = erlang:send_after(State1#state.timeout, self(), update),
    {noreply, State1#state{ timer = TRef }};
```

`internal_update` 本身是核心状态转换函数：

```erlang
internal_update(State0 = #state{memory_limit = MemLimit,
                                alarmed      = Alarmed,
                                alarm_funs   = {AlarmSet, AlarmClear}}) ->
    State1 = update_process_memory(State0),
    ProcMem = State1#state.process_memory,
    NewAlarmed = ProcMem > MemLimit,
    case {Alarmed, NewAlarmed} of
        {false, true} -> emit_update_info(set, ProcMem, MemLimit),
                         AlarmSet({{resource_limit, memory, node()}, []});
        {true, false} -> emit_update_info(clear, ProcMem, MemLimit),
                         AlarmClear({resource_limit, memory, node()});
        _             -> ok
    end,
    State1#state{alarmed = NewAlarmed}.
```

这里有两个值得关注的细节：

第一，`AlarmSet` 和 `AlarmClear` 是函数引用，默认是 `alarm_handler:set_alarm/1` 和 `alarm_handler:clear_alarm/1`，但可以在 `start_link/3` 时传入自定义函数。这使得测试时可以注入 mock 函数，不需要真实的 `alarm_handler`，体现了依赖注入的思想。

第二，告警的触发是**边缘触发**（edge-triggered）而非**水平触发**（level-triggered）：只在状态从 `false` 变 `true` 时发 `set_alarm`，从 `true` 变 `false` 时发 `clear_alarm`，保持不变时（`_`）不做任何事。这防止了每次轮询都向 `alarm_handler` 发消息，减少了系统总线上的噪音。`alarmed` 字段保存上一次的状态，作为边缘检测的"前值"。

告警键是 `{resource_limit, memory, node()}`，包含节点名。这允许集群中不同节点独立触发告警，`rabbit_alarm` 在收到时知道是哪个节点超阈值。

### 5.4 告警传播链

当内存超过阈值，`vm_memory_monitor` 调用 `alarm_handler:set_alarm({resource_limit, memory, node()})`，`rabbit_alarm` 收到后遍历所有注册的 reader 进程：

```erlang
%% rabbit_alarm.erl
handle_info({alarm_handler, {set_alarm, {memory_limit_alarm, _}}}, State) ->
    ShouldBlock = case State#state.alarms of
        [] -> true;   %% 之前无告警，这是新告警
        _  -> false   %% 已有告警，不重复发送
    end,
    ...
    [rabbit_reader:conserve_resources(Pid, memory, true) || Pid <- ReaderPids],
```

每个 Reader 收到 `{conserve_resources, memory, true}` 消息后更新 `blocked_by` 集合，并调用 `control_throttle`（同第一章）。`blocked_by` 集合可同时包含 `{resource, memory}`、`{resource, disk}` 和 `flow`，任意一个存在就保持 `blocked` 状态，全部移除才解锁。

### 5.5 credit_flow 的 deferred grant 与背压传导链

第一章已详细分析了 Reader→Channel 的 credit_flow。实际上整个传导链更长：

```
rabbit_msg_store ←ack/grant─ rabbit_amqqueue_process ←ack/grant─ rabbit_channel ←ack/grant─ rabbit_reader
```

`rabbit_amqqueue_process` 既是 Channel 的下游（接收消息），也是 msg_store 的上游（写消息）。当 msg_store 处理不过来时，它会阻塞队列进程；队列进程被阻塞后，`grant` 延迟发送（`credit_deferred`），Channel 的信用耗尽；Channel 被阻塞后，`grant` 延迟发送给 Reader，Reader 信用耗尽，停止读取 TCP 数据。这就是 `credit_flow.erl` 注释里描述的背压链：

```
%% Credit flows left to right when processes send messages down the chain:
%%   reader -> channel -> queue_process -> msg_store.
%% If the msg_store has a backlog, it will block the queue_process,
%% which will block the channel, and finally the reader will be blocked.
```

`deferred grant` 的核心价值在于保证这条链的有效性：中游不会在自己被堵塞的情况下向上游放行，防止"中间节点无视背压"导致上游继续灌入数据、中间节点无限积压。

### 5.6 rabbit_limiter：prefetch 的实现细节

`basic.qos{prefetch_count=N}` 限制每个 Channel 最多有 N 条未 ack 的消息在飞行。`rabbit_limiter` 是一个 `gen_server`，维护 `unacked` 计数；`rabbit_queue_consumers` 在投递消息前调用 `can_send/3` 检查是否还有 prefetch 配额。

容易误解的是：prefetch 限制的是**单个 Channel 上所有消费者的未 ack 总数**（global prefetch），而非单个消费者。`basic.qos{global=true}` 是 Channel 级别限制，`global=false`（默认）是每个消费者单独限制。两者在 `rabbit_limiter` 里通过 `limit_prefetch` 的参数区分。

另一个细节：当消费者 ack 消息后，limiter 从 `blocked` 变为 `unblocked`，它会向队列进程发送 `{unblock, ConsumerTag}` 消息。队列进程在 `handle_info({unblock, ...})` 里立即调用 `attempt_delivery`，尝试向该消费者推送之前因 prefetch 满而积压的消息。这是一个**消费者 ack 触发立即推送**的设计，保证 prefetch 窗口始终尽量满载，最大化吞吐。

---

## 第六章：元数据存储——Khepri projection 的一致性窗口

### 6.1 Khepri 的本质与 Mnesia 的对比

Mnesia 是 Erlang 自带的分布式数据库，采用 2PC（两阶段提交）保证分布式写入一致性，但 2PC 在网络分区时会阻塞，且 Mnesia 的分区恢复需要人工干预（`rabbit-node forget`）。

Khepri 基于 Ra（Erlang 实现的 Raft），写操作必须经过 Raft 共识（多数节点 ack），读操作可以从本地 ETS 缓存直接读取。这带来了本质上不同的 CAP 取舍：Khepri 在网络分区时少数派节点会拒绝写入（CP），而不会像 Mnesia 那样出现脑裂。

### 6.2 ETS Projection：读操作的零延迟

Khepri 的写操作走 Raft，延迟在几十毫秒量级（需要多数节点 disk fsync）。但消息路由（`rabbit_exchange:lookup`、`rabbit_db_binding:get_direct_routing`）是极热路径，不能走 Raft。

解决方案是 **ETS projection**：Khepri 每次提交写操作后，通过 projection 回调自动把最新状态同步到本地 ETS 表。所有读操作直接查 ETS，不经过 Khepri（也不走网络）：

```erlang
%% rabbit_db_queue.erl
get_all(VHostName) ->
    Pattern = amqqueue:pattern_match_on_name(rabbit_misc:r(VHostName, queue)),
    ets:match_object(?KHEPRI_PROJECTION, Pattern).    %% 纯本地 ETS，O(N) 但无锁
```

### 6.3 一致性窗口：ETS 与 Khepri 的短暂不一致

这里存在一个**微小但真实的不一致窗口**：Khepri 写入（Raft commit）完成后，projection 回调是在同一个 Erlang 进程里同步执行的（`khepri_projection` 模块在 Ra 的 `apply` 阶段调用），所以对于**本地节点**，ETS 和 Khepri 是原子同步的——Raft commit 的同时 ETS 就更新了。

但对于**其他节点**：远端节点的 Ra follower apply 该日志条目的时间取决于日志复制延迟。在 follower apply 之前，该节点的 ETS 还是旧值。这意味着：

- 节点 A 新建了一个 queue，立即告知节点 B
- 节点 B 查询本地 ETS，可能查不到这个 queue（follower 尚未 apply）
- 几十毫秒后 follower apply，ETS 更新，查询成功

实践中 AMQP 客户端的连接通常固定在某个节点，不同节点的元数据不一致窗口一般不可见。但跨节点的 shovel/federation 或管理插件的 HTTP API 可能遇到这个窗口。

### 6.4 adv_create：queue declare 的原子性

队列声明（`queue.declare`）需要"队列不存在则创建，存在则返回现有队列"这个原子语义，不能有 TOCTOU（时间检查与时间使用之间的竞争）。Khepri 用 `adv_create` 实现：

```erlang
create_or_get(Q) ->
    QueueName = amqqueue:get_name(Q),
    Path = khepri_queue_path(QueueName),
    case rabbit_khepri:adv_create(Path, Q) of
        {error, {khepri, mismatching_node,
                 #{node_props := #{data := ExistingQ}}}} ->
            %% 路径已存在，返回现有队列
            {existing, ExistingQ};
        {ok, _} ->
            {created, Q};
        Error ->
            Error
    end.
```

`adv_create` 等价于 Raft 级别的 compare-and-swap：它提交一个"如果路径不存在则写入"的条件命令。如果两个节点同时发起同一 queue 的 declare，Raft 保证只有一个命令被 commit，另一个在 apply 时发现路径已存在而返回 `mismatching_node` 错误——不需要任何客户端侧的锁或重试逻辑。

这个设计在 Mnesia 时代是用 `mnesia:transaction` + `dirty_read` 的手工乐观锁实现的，Khepri 让它变成了一个语义清晰的原子操作。

### 6.5 Khepri 路径树的设计意图

Khepri 用层次路径（类似文件系统树）组织数据：

```
[rabbitmq]
  └── [vhosts]
        └── ["/"]
              ├── [exchanges]
              │     └── ["amq.topic"] → exchange 数据
              │           └── [bindings]
              │                 └── [queue] → [myqueue] → ["routing.key"] → binding 数据
              └── [queues]
                    └── ["myqueue"] → queue 数据
```

这个树形结构的核心价值是**原子子树操作**：删除 vhost 只需原子删除 `[rabbitmq, vhosts, VHost]` 子树，一次 Raft 命令完成，不需要事先枚举所有 exchange/queue/binding 逐一删除。相比之下，Mnesia 的多表结构需要多次事务、多个表的清理操作，存在部分成功的风险。

---

## 第七章：Quorum Queue——maybe_enqueue 幂等与 release_cursor 自适应

### 7.1 Quorum Queue 与经典队列的根本差异

经典队列（CQ）是单进程模型：所有操作在 `rabbit_amqqueue_process` 单进程里串行执行，状态不复制，宕机丢消息。Quorum Queue（QQ）把队列状态建模为 **Raft 状态机**：每次操作是一个 Raft 命令，leader 提交后复制到多数副本，才向客户端回复。这意味着：

- 即使 leader 宕机，任意多数节点存活都能选出新 leader，消息不丢
- 所有副本以完全相同的顺序 apply 命令，状态严格一致
- 不存在"主挂了，消息在 flight 中丢失"的情形

代价是：每次入队都需要 Raft 共识（至少一次 disk fsync + 多数节点 ack），延迟比经典队列高。

### 7.2 maybe_enqueue：为什么需要幂等去重

发布方通过 `rabbit_fifo_client` 向 Raft leader 提交 `#enqueue{pid, seq, msg}` 命令，`seq` 是发布方进程维护的单调递增序列号。这个 seq 的存在是为了处理**网络重传**：如果 leader apply 了命令但在回复前崩溃，发布方收不到 ack，会向新 leader 重新提交。新 leader 需要识别并丢弃重复命令。

`maybe_enqueue` 处理三种情形：

```erlang
maybe_enqueue(RaftIdx, Ts, From, Seq, RawMsg, Effects, State0) ->
    case From of
        undefined ->
            %% 内部消息（如死信、requeue），无需 seq 跟踪
            ...;
        _ ->
            case maps:get(From, State0#?STATE.enqueuers, undefined) of
                undefined ->
                    %% 新发布方，注册并处理
                    State1 = State0#?STATE{enqueuers = maps:put(From, #enqueuer{next_seqno = Seq + 1}, ...)},
                    enqueue(RaftIdx, Ts, RawMsg, State1, Effects);
                #enqueuer{next_seqno = Seq} = Enqueuer ->
                    %% Seq 匹配预期 → 正常入队
                    enqueue(RaftIdx, Ts, RawMsg,
                            State0#?STATE{enqueuers = maps:put(From, Enqueuer#enqueuer{next_seqno = Seq + 1}, ...)},
                            Effects);
                #enqueuer{next_seqno = Next} when Seq > Next ->
                    %% Seq 超前（乱序到达）→ 暂存，等待补全
                    {out_of_sequence, State0, Effects};
                #enqueuer{next_seqno = Next} when Seq < Next ->
                    %% Seq 落后（重复提交）→ 幂等丢弃
                    {duplicate, State0, Effects}
            end
    end.
```

**乱序（out_of_sequence）** 会怎样？`apply_enqueue` 收到 `{out_of_sequence, ...}` 时返回 `not_enqueued`，Ra 不会向发布方发送 confirm，发布方重试，直到缺失的中间序号先到达被处理。在正常运行中，这种情况极罕见（单连接的命令通常严格有序）；在故障恢复时可能发生。

**重复（duplicate）** 直接返回 `ok`（对发布方来说消息已被 confirm），不真正入队。这保证了在 leader 切换后，发布方重传的消息不会被重复入队。

### 7.3 enqueue_count 与 release_cursor 的动态阈值

每次成功入队后：

```erlang
State3 = State2#?STATE{enqueue_count = State2#?STATE.enqueue_count + 1},
maybe_store_release_cursor(RaftIdx, State3)
```

```erlang
maybe_store_release_cursor(RaftIdx,
    #?STATE{cfg = #cfg{release_cursor_interval = {Base, C}} = Cfg,
            enqueue_count = EC, ...} = State0)
  when EC >= C ->
    case messages_total(State0) of
        0 ->
            %% 队列已清空 → 直接重置，不需要序列化状态
            State0#?STATE{enqueue_count = 0};
        Total ->
            %% 动态调整下次触发间隔
            Interval = min(max(Total, Base), ?RELEASE_CURSOR_EVERY_MAX),
            ...
            Dehydrated = dehydrate_state(State),
            Cursor = {release_cursor, RaftIdx, Dehydrated},
            State#?STATE{enqueue_count = 0, release_cursors = lqueue:in(Cursor, Cursors0)}
    end;
```

`?RELEASE_CURSOR_EVERY_MAX`（默认 3_200_000）是上限。动态间隔的计算逻辑：

- `Interval = min(max(Total, Base), 3_200_000)`
- `Base` 是配置的基准值（默认 2048）
- `Total` 是当前队列深度

当队列很深（例如积压了 100 万条消息），`Interval` 接近 `Total`，即每当队列深度翻倍时才触发一次 release_cursor。这避免了队列积压时频繁序列化状态（每次序列化都有 CPU + I/O 开销），同时保证日志不会无限增长。

当队列清空时（`messages_total = 0`），跳过序列化直接重置——此时状态最轻量，重置 enqueue_count 就够了，没有必要写快照。

### 7.4 dehydrate_state 与 rehydrate_state：消息体不进快照

```erlang
dehydrate_state(#?STATE{cfg = Cfg, consumers = Consumers,
                         messages = Messages, returns = Returns,
                         ...} = State) ->
    %% 消息队列里的每条消息只保留 header（大小、过期时间、投递次数）
    %% 消息体（raw_msg）不放进快照——它在 Ra 日志/segment 文件里，恢复时从那里读
    State#?STATE{
        messages = dehydrate_messages(Messages),
        returns  = dehydrate_messages(Returns),
        consumers = dehydrate_consumers(Consumers),
        ...
    }.
```

快照里的消息只有元数据（`msg_header`）和 Raft 索引，没有消息体。重启后 `rehydrate_state` 从 Ra segment 文件里按索引重新加载消息体。这样快照体积远小于实际内存占用，序列化速度快，传输开销小（用于新节点的 snapshot transfer）。

### 7.5 checkout：消息分发与 service_queue 优先级

`checkout` 是 apply 入队/settle(ack)/credit 命令后尝试把消息分配给等待中消费者的函数。`service_queue` 是一个优先队列，存放有信用且可接收消息的消费者：

```erlang
checkout_one(#{index := RaftIdx} = Meta, State0) ->
    case priority_queue:out(State0#?STATE.service_queue) of
        {{value, ConsumerId}, SQ} ->
            Consumer = maps:get(ConsumerId, State0#?STATE.consumers),
            case take_next_msg(State0) of
                {ConsumerMsg, State1} ->
                    %% 扣减消费者信用，产生 send_msg Effect
                    State2 = update_consumer(ConsumerId, Consumer, ConsumerMsg, State1),
                    Effects = [{send_msg, ConsumerPid, ConsumerMsg, [noconnect]}],
                    {success, ConsumerId, MsgId, ConsumerMsg, Effects, State2};
                empty ->
                    %% 队列空，停止分发
                    {queue_empty, State0}
            end;
        {empty, _} ->
            %% 无可用消费者
            {no_consumers, State0}
    end.
```

`priority_queue` 实现了消费者公平调度：每个消费者有一个优先级（通常相同），`priority_queue:out` 按优先级+插入顺序出队，保证消息均匀分发。`take_next_msg` 优先从 `returns`（nack+requeue 的消息）取，再从 `messages` 取，实现死信前的优先重试。

`{send_msg, ConsumerPid, ConsumerMsg, [noconnect]}` 是 Ra 的 Effect——只在 leader 节点执行，follower apply 同样的命令但不执行该 Effect，防止消息被重复推送给消费者进程。`noconnect` 标记表示即使进程不在当前节点也直接发（Ra 负责跨节点路由）。

---

## 第八章：经典队列进程模型（rabbit_amqqueue_process）

经典队列（Classic Queue）的每个队列实例都是一个独立的 Erlang 进程，模块为 `rabbit_amqqueue_process`。它是 RabbitMQ 中最核心、最复杂的 gen_server2 进程之一，承担消息入队、消费者调度、确认、溢出处理等全部职责。

### 8.1 #q{} record：队列进程的完整状态

```erlang
-record(q, {
    q                   :: amqqueue:amqqueue(),   %% 队列元数据（名称、参数、持久化标志等）
    exclusive_consumer  :: none | {ctag(), pid()}, %% 独占消费者（exclusive consume）
    has_had_consumers   :: boolean(),              %% 是否曾经有过消费者（影响 auto-delete）
    backing_queue       :: module(),               %% 后端存储模块（rabbit_variable_queue 等）
    backing_queue_state :: any(),                  %% 后端存储的不透明状态
    consumers           :: rabbit_queue_consumers:state(), %% 消费者状态（见第九章）
    expires             :: undefined | integer(),  %% x-expires TTL（毫秒）
    sync_timer_ref      :: undefined | reference(),%% 定期 sync 的定时器
    rate_timer_ref      :: undefined | reference(),%% 消息速率统计定时器
    expiry_timer_ref    :: undefined | reference(),%% 队列过期定时器
    stats_timer         :: rabbit_event:state(),   %% 统计事件定时器
    msg_id_to_channel   :: gb_trees:tree(),        %% MsgId -> {ChPid, MsgSeqNo}，用于 confirm
    ttl                 :: undefined | integer(),  %% x-message-ttl（毫秒）
    ttl_timer_ref       :: undefined | reference(),%% 消息 TTL 扫描定时器
    ttl_timer_expiry    :: undefined | integer(),  %% 下次 TTL 扫描时间点
    senders             :: pmon:pmon(),            %% 发送者进程监控集合
    dlx                 :: undefined | rabbit_types:exchange_name(), %% 死信 exchange
    dlx_routing_key     :: undefined | binary(),   %% 死信 routing key
    max_length          :: undefined | integer(),  %% x-max-length
    max_bytes           :: undefined | integer(),  %% x-max-length-bytes
    max_priority        :: undefined | integer(),  %% x-max-priority
    consumer_utilisation :: undefined | float(),   %% 消费者利用率（0.0~1.0）
    queue_tags          :: map(),                  %% 队列标签（用于 stream 等扩展）
    overflow            :: drop_head | reject_publish | reject_publish_dlx
}).
```

这个 record 把队列的所有运行时状态集中在一个进程里。`backing_queue` 和 `backing_queue_state` 是后端存储的抽象接口，默认实现是 `rabbit_variable_queue`，它负责消息在内存和磁盘之间的流转（详见第五章）。`consumers` 字段委托给 `rabbit_queue_consumers` 模块管理（详见第九章）。`msg_id_to_channel` 是一棵 gb_tree，记录每条待确认消息对应的 channel 进程和序列号，confirm 完成后从树中删除。

### 8.2 deliver_or_enqueue：入队的第一个决策点

消息到达队列进程后，首先经过 `deliver_or_enqueue`：

```erlang
deliver_or_enqueue(Delivery = #delivery{message = Message, sender = SenderPid},
                   Delivered, State = #q{backing_queue = BQ,
                                         backing_queue_state = BQS}) ->
    {Confirm, State1} = send_mandatory_or_confirm(Delivery, State),
    case attempt_delivery(Delivery, Delivered, State1) of
        {delivered, State2} ->
            %% 直接投递给消费者，无需入队
            {Confirm, State2};
        {undelivered, State2} ->
            %% 没有可用消费者，走入队路径
            State3 = maybe_drop_head(State2),
            BQS1 = BQ:publish(Message, ..., BQS),
            {Confirm, State3#q{backing_queue_state = BQS1}}
    end.
```

这里有两条路径：`attempt_delivery` 成功时消息直接推给消费者，完全绕过后端存储，是零拷贝的快路径；失败时才调用 `BQ:publish` 写入 `backing_queue`，同时先执行 `maybe_drop_head` 检查溢出策略。

`send_mandatory_or_confirm` 处理 mandatory 标志和 publisher confirm：如果消息是 mandatory 且无法路由，需要立即 return；如果开启了 confirm，则把 `{ChPid, MsgSeqNo}` 记录到 `msg_id_to_channel`，等消息落盘或被 ack 后再发 `basic.ack`。

### 8.3 attempt_delivery：直接投递的条件判断

```erlang
attempt_delivery(Delivery = #delivery{sender = SenderPid, message = Message},
                 Delivered, State = #q{consumers = Consumers,
                                       backing_queue = BQ,
                                       backing_queue_state = BQS}) ->
    case rabbit_queue_consumers:deliver(
           fun(AckRequired) ->
               %% 如果需要 ack，先写入 backing_queue 获得 AckTag
               case AckRequired of
                   true  -> BQ:publish_delivered(Message, ..., BQS);
                   false -> {undefined, BQS}
               end
           end,
           Delivery, Consumers) of
        {delivered, AckTag, Consumers1} ->
            %% 消费者接收了消息
            State1 = State#q{consumers = Consumers1,
                             backing_queue_state = BQS1},
            {delivered, State1};
        {undelivered, Consumers1} ->
            {undelivered, State#q{consumers = Consumers1}}
    end.
```

关键细节在于 `AckRequired` 分支：当消费者开启了 `no_ack=false`（需要显式 ack）时，消息必须先通过 `BQ:publish_delivered` 写入后端存储并获得 `AckTag`，这样消费者 nack 时才能通过 `AckTag` 找回消息重新入队。`no_ack=true` 时则完全跳过存储，消息发出即丢弃，吞吐最高但无可靠性保证。

### 8.4 maybe_drop_head：三种溢出策略

当队列长度或字节数超过 `x-max-length` / `x-max-length-bytes` 时，`maybe_drop_head` 按 `overflow` 字段决定处理方式：

```erlang
maybe_drop_head(State = #q{overflow = Overflow}) ->
    case Overflow of
        drop_head ->
            %% 丢弃队头最老的消息，为新消息腾出空间
            maybe_drop_head1(State);
        reject_publish ->
            %% 拒绝发布（配合 credit_flow 背压，见第二章）
            State;
        reject_publish_dlx ->
            %% 拒绝发布，同时把被挤出的消息送往死信队列
            State
    end.

maybe_drop_head1(State = #q{backing_queue = BQ, backing_queue_state = BQS}) ->
    case over_max_length(State) of
        true ->
            %% 取出队头消息
            {Msg, _AckTag, BQS1} = BQ:fetch(true, BQS),
            %% 尝试死信投递（如果配置了 x-dead-letter-exchange）
            State1 = dead_letter_rejected_msgs([Msg], State#q{backing_queue_state = BQS1}),
            maybe_drop_head1(State1);
        false ->
            State
    end.
```

`drop_head` 是默认策略，循环调用直到队列长度合规。`reject_publish` 依赖 `credit_flow` 的背压机制——队列进程停止向 channel 进程发放 credit，channel 进程因此阻塞，不再向队列发送消息（详见第二章）。`reject_publish_dlx` 在拒绝的同时把溢出消息路由到死信 exchange，适合需要审计溢出消息的场景。

### 8.5 run_message_queue：消费者就绪后的批量分发

当新消费者注册、或消费者 ack 释放了 credit 后，队列进程调用 `run_message_queue` 把积压消息批量推出：

```erlang
run_message_queue(ActiveConsumersChanged, State) ->
    case is_empty(State) of
        true  -> State;
        false ->
            case rabbit_queue_consumers:deliver(
                   fun(AckRequired) -> fetch_from_backing_queue(AckRequired, State) end,
                   none, State#q.consumers) of
                {delivered, AckTag, Consumers1} ->
                    State1 = State#q{consumers = Consumers1},
                    run_message_queue(false, State1);   %% 递归继续分发
                {undelivered, _} ->
                    State   %% 无可用消费者，停止
            end
    end.
```

这是一个尾递归循环，每次从 `backing_queue` 取一条消息尝试投递，直到队列为空或没有可用消费者为止。`fetch_from_backing_queue` 调用 `BQ:fetch` 取出消息，如果 `AckRequired=true` 则保留 `AckTag` 以便后续 ack/nack。

`ActiveConsumersChanged` 标志用于触发统计更新——当消费者数量变化时，需要重新计算 `consumer_utilisation`（消费者利用率），这个指标会上报到 management 插件的监控面板。

### 8.6 confirm_messages：confirm 路径的收尾

消息落盘后，`backing_queue` 通过回调通知队列进程，队列进程调用 `confirm_messages` 向 channel 发送 `basic.ack`：

```erlang
confirm_messages(MsgIds, State = #q{msg_id_to_channel = MTC}) ->
    {CMs, MTC1} =
        lists:foldl(
          fun(MsgId, {CMs, MTC0}) ->
              case gb_trees:lookup(MsgId, MTC0) of
                  {value, {SenderPid, MsgSeqNo}} ->
                      %% 按 channel 分组，批量发送 ack
                      {maps:update_with(SenderPid,
                                        fun(MsgSeqNos) -> [MsgSeqNo | MsgSeqNos] end,
                                        [MsgSeqNo], CMs),
                       gb_trees:delete(MsgId, MTC0)};
                  none ->
                      {CMs, MTC0}
              end
          end, {#{}, MTC}, MsgIds),
    maps:foreach(fun(Pid, MsgSeqNos) ->
                     rabbit_misc:confirm_to_sender(Pid, MsgSeqNos)
                 end, CMs),
    State#q{msg_id_to_channel = MTC1}.
```

`msg_id_to_channel` 是一棵 gb_tree（有序树），按 MsgId 查找对应的 `{SenderPid, MsgSeqNo}`。`confirm_messages` 把同一个 channel 的多个 `MsgSeqNo` 合并成一次 `rabbit_misc:confirm_to_sender` 调用，减少跨进程消息数量。channel 收到后再组装 `basic.ack` 帧发给客户端，`delivery_tag` 范围可以用 `multiple=true` 批量确认。

---

## 第九章：消费者管理（rabbit_queue_consumers）

`rabbit_queue_consumers` 是经典队列的消费者状态管理模块，负责维护所有消费者的注册信息、信用状态、阻塞状态，以及把消息分发给合适的消费者。它的核心数据结构是两个 priority_queue 加一个进程字典。

### 9.1 #cr{} record：per-channel 消费者状态

每个 channel 进程对应一个 `#cr{}` record，存储在队列进程的进程字典中，key 为 `{consumer_channel, ChPid}`：

```erlang
-record(cr, {
    ch_pid               :: pid(),           %% channel 进程 PID
    monitor              :: reference(),     %% 对 channel 进程的监控引用
    acktags              :: sets:set(),      %% 已发出但未 ack 的消息 AckTag 集合
    consumer_count       :: non_neg_integer(),%% 该 channel 上的消费者数量
    %% 该 channel 上所有消费者的 unsent_message_count 之和超过阈值时阻塞
    unsent_message_count :: non_neg_integer(),
    %% 被阻塞的消费者队列（priority_queue，按 x-priority 排序）
    blocked_consumers    :: priority_queue:q(),
    %% limiter 状态（与 basic.qos prefetch_count 联动）
    limiter              :: rabbit_limiter:client(),
    %% AMQP 1.0 link 状态（每个 link 独立的 credit）
    link_states          :: map()
}).
```

进程字典存储 `#cr{}` 而非 ETS 的原因与 `credit_flow` 相同：消费者调度是高频路径，进程字典的 O(1) 读写比 ETS 的原子操作快，且不需要跨进程共享。`acktags` 是一个 sets，记录所有已发出但未收到 ack 的消息 AckTag，用于 channel 崩溃时的消息恢复（把这些消息重新入队）。

`unsent_message_count` 是一个软性背压计数器：每次向该 channel 发送一条消息就加一，收到 ack 时减一。当计数超过 `unsent_message_limit`（默认 200）时，该 channel 上的所有消费者被移入 `blocked_consumers`，停止接收新消息，直到 ack 把计数降回阈值以下。

### 9.2 deliver：消费者选择与消息分发

`deliver/5` 是消费者调度的核心函数，从 `active_consumers`（可用消费者优先队列）中选出最高优先级的消费者，调用回调函数获取消息，然后通过 `deliver_to_consumer` 推送：

```erlang
deliver(FetchFun, Delivery, Consumers) ->
    case priority_queue:out(Consumers#state.active_consumers) of
        {empty, _} ->
            {undelivered, Consumers};
        {{value, QEntry}, ActiveConsumers1} ->
            {ChPid, Consumer} = QEntry,
            CR = ch_record(ChPid),
            %% 检查该消费者是否仍然可用（未被阻塞、有 credit）
            case is_ch_blocked(CR) orelse
                 rabbit_limiter:is_suspended(CR#cr.limiter) of
                true ->
                    %% 消费者被阻塞，移入 blocked_consumers
                    Consumers1 = block_consumer(Consumer, Consumers),
                    deliver(FetchFun, Delivery, Consumers1);
                false ->
                    %% 调用 FetchFun 获取消息（可能触发 backing_queue 写入）
                    {Msg, AckTag} = FetchFun(Consumer#consumer.ack_required),
                    deliver_to_consumer(Msg, AckTag, ChPid, Consumer, CR, Consumers1)
            end
    end.
```

`active_consumers` 是一个 `priority_queue`，按消费者的 `x-priority` 参数排序（值越大优先级越高）。同优先级的消费者按 FIFO 顺序轮转，实现公平调度。`is_ch_blocked` 检查两个条件（见 9.3 节），任一满足则把消费者移入 `blocked_consumers` 并递归尝试下一个。

`deliver_to_consumer` 最终调用 `rabbit_channel:deliver` 把消息推给 channel 进程，channel 再组装 `basic.deliver` 帧发给客户端：

```erlang
deliver_to_consumer(Msg, AckTag, ChPid, Consumer, CR, Consumers) ->
    ok = rabbit_channel:deliver(ChPid, Consumer, Msg),
    %% 更新 acktags 和 unsent_message_count
    CR1 = CR#cr{acktags              = sets:add_element(AckTag, CR#cr.acktags),
                unsent_message_count = CR#cr.unsent_message_count + 1},
    %% 检查是否需要因 unsent_message_count 超限而阻塞
    Consumers1 = maybe_block(Consumer, CR1, Consumers),
    {delivered, AckTag, Consumers1}.
```

### 9.3 is_ch_blocked：双条件阻塞判断

```erlang
is_ch_blocked(#cr{unsent_message_count = Count, limiter = Limiter}) ->
    Count >= ?UNSENT_MESSAGE_LIMIT orelse rabbit_limiter:is_suspended(Limiter).
```

`is_ch_blocked` 检查两个独立的阻塞条件，任一为真则该 channel 上的消费者全部停止接收消息：

第一个条件是 `unsent_message_count >= UNSENT_MESSAGE_LIMIT`（默认 200）。这是队列进程侧的软性背压：如果 channel 进程处理消息的速度跟不上队列的推送速度，未 ack 消息数量会持续增长，超过阈值后队列主动停止推送，等待 channel 消化积压。这个机制防止了队列进程把大量消息堆积在 channel 进程的消息邮箱里，避免 channel 进程内存暴涨。

第二个条件是 `rabbit_limiter:is_suspended`，对应 `basic.qos` 的 `prefetch_count` 限制。当消费者设置了 `prefetch_count=N` 时，`rabbit_limiter` 跟踪该 channel 上未 ack 的消息数量，超过 N 后返回 `suspended=true`，队列停止向该 channel 推送消息，直到消费者 ack 把计数降下来。

两个条件的区别在于粒度：`unsent_message_count` 是队列进程对 channel 的整体感知，`rabbit_limiter` 是 channel 自身对 prefetch 的精确控制。前者是粗粒度的保护机制，后者是细粒度的流控机制。

### 9.4 subtract_acks：ack 处理与 limiter 联动

消费者发送 `basic.ack` 后，channel 进程通知队列进程，队列进程调用 `subtract_acks` 更新 `#cr{}` 状态：

```erlang
subtract_acks(ChPid, AckTags, Consumers, Fun) ->
    CR = ch_record(ChPid),
    %% 从 acktags 集合中移除已 ack 的 AckTag
    AcksRemaining = sets:subtract(CR#cr.acktags, sets:from_list(AckTags)),
    %% 通知 limiter 释放 credit（prefetch_count 计数减少）
    ok = rabbit_limiter:ack(CR#cr.limiter, length(AckTags)),
    %% 更新 unsent_message_count
    CR1 = CR#cr{acktags              = AcksRemaining,
                unsent_message_count = max(0, CR#cr.unsent_message_count - length(AckTags))},
    store_ch_record(CR1),
    %% 执行回调（通常是 possibly_unblock）
    Fun(CR1).
```

`sets:subtract` 批量移除已 ack 的 AckTag，时间复杂度 O(N log N)。`rabbit_limiter:ack` 把 prefetch 计数减少对应数量，如果 limiter 之前处于 suspended 状态，此时可能解除暂停。`unsent_message_count` 同步减少，为后续的 `possibly_unblock` 判断提供最新数据。

### 9.5 possibly_unblock 与 unblock：解锁路径

`subtract_acks` 的回调通常是 `possibly_unblock`，它检查 channel 是否可以从阻塞状态恢复：

```erlang
possibly_unblock(Fun, ChPid, Consumers) ->
    CR = ch_record(ChPid),
    case is_ch_blocked(CR) of
        true  ->
            %% 仍然阻塞，不做任何事
            Consumers;
        false ->
            %% 可以解锁，把 blocked_consumers 里的消费者移回 active_consumers
            unblock(Fun, CR, Consumers)
    end.

unblock(Fun, CR = #cr{blocked_consumers = BlockedQ}, Consumers) ->
    case priority_queue:out(BlockedQ) of
        {empty, _} ->
            Consumers;
        {{value, Consumer}, BlockedQ1} ->
            CR1 = CR#cr{blocked_consumers = BlockedQ1},
            store_ch_record(CR1),
            %% 把消费者重新加入 active_consumers
            Consumers1 = add_consumer(Consumer, Consumers),
            unblock(Fun, CR1, Consumers1)
    end.
```

`unblock` 把 `blocked_consumers` 里的所有消费者逐一移回 `active_consumers`，恢复它们参与调度的资格。这个操作完成后，队列进程会收到通知，触发 `run_message_queue` 重新尝试分发积压消息。

整个阻塞-解锁路径形成了一个完整的背压闭环：消息推送过快 → `unsent_message_count` 超限 → 消费者移入 `blocked_consumers` → 队列停止推送 → 消费者 ack → `subtract_acks` 减少计数 → `possibly_unblock` 检查 → `unblock` 恢复消费者 → `run_message_queue` 继续推送。

### 9.6 Single Active Consumer

Single Active Consumer（SAC）是 RabbitMQ 3.8 引入的特性，通过 `x-single-active-consumer=true` 参数启用。它保证同一时刻只有一个消费者处于活跃状态，其余消费者处于等待状态，活跃消费者断开后自动切换到下一个等待消费者。

在 `rabbit_queue_consumers` 中，SAC 的实现依赖 `single_active_consumer_on` 标志和 `waiting_consumers` 队列：

```erlang
activate_next_consumer(Consumers = #state{single_active_consumer_on = true,
                                           waiting_consumers = WaitingQ}) ->
    case priority_queue:out(WaitingQ) of
        {empty, _} ->
            %% 没有等待消费者，队列进入无消费者状态
            Consumers#state{active_consumer = none};
        {{value, Consumer}, WaitingQ1} ->
            %% 激活下一个等待消费者
            Consumers1 = Consumers#state{
                active_consumer  = Consumer,
                waiting_consumers = WaitingQ1
            },
            %% 通知该消费者它已成为活跃消费者（发送 consumer.update 事件）
            notify_consumer_activated(Consumer),
            Consumers1
    end.
```

SAC 与普通消费者的区别在于：普通模式下所有消费者都在 `active_consumers` 里竞争消息；SAC 模式下只有 `active_consumer` 字段指向的那一个消费者参与调度，其余消费者在 `waiting_consumers` 里等待。这个设计适合需要严格顺序消费的场景，但会牺牲并发吞吐。

---

## 第十章：队列类型抽象层（rabbit_queue_type）

RabbitMQ 支持多种队列类型：经典队列（Classic）、仲裁队列（Quorum）、流队列（Stream）。`rabbit_queue_type` 是这三种类型的统一多态分发层，channel 进程只需调用 `rabbit_queue_type` 的接口，无需感知底层队列类型。

### 10.1 -callback 行为定义

`rabbit_queue_type` 通过 `-callback` 定义了所有队列类型必须实现的行为接口：

```erlang
-callback deliver(Deliveries, QState) ->
    {ok, QState, Actions} | {error, term()}
    when Deliveries :: [{rabbit_types:delivery(), stateful | stateless}],
         QState     :: any(),
         Actions    :: actions().

-callback consume(amqqueue:amqqueue(), consume_spec(), QState) ->
    {ok, QState, Actions} | {error, term()}.

-callback cancel(amqqueue:amqqueue(), rabbit_types:ctag(),
                 term(), rabbit_types:username(), QState) ->
    {ok, QState, Actions} | {error, term()}.

-callback handle_event(amqqueue:amqqueue(), Event, QState) ->
    {ok, QState, Actions} | {eol, Actions} | {error, term()}
    when Event :: {down, pid(), term()} | {timeout, term()} | term().

-callback settle(amqqueue:amqqueue(), settle_op(), rabbit_types:ctag(),
                 [non_neg_integer()], QState) ->
    {ok, QState, Actions}.

-callback info(amqqueue:amqqueue(), all_keys | rabbit_types:info_keys()) ->
    rabbit_types:infos().

-callback stat(amqqueue:amqqueue()) ->
    {ok, non_neg_integer(), non_neg_integer()}.
```

每个回调返回 `{ok, QState, Actions}`，其中 `Actions` 是一个副作用列表，包含需要在 channel 层执行的操作，例如 `{send_credit_reply, ...}`、`{send_drained, ...}`、`{notify_decorators, ...}` 等。这个设计把队列内部状态变更（`QState`）和对外副作用（`Actions`）分离，使得队列类型的实现可以是纯函数式的。

### 10.2 deliver0：按队列类型分组投递

channel 进程调用 `rabbit_queue_type:deliver` 时，`deliver0` 把消息按队列类型分组，批量调用各类型的 `deliver` 回调：

```erlang
deliver0(Qs, Delivery, Options, QueueStates) ->
    %% 按队列类型分组
    ByType = lists:foldl(
               fun(Q, Acc) ->
                   Type = amqqueue:get_type(Q),
                   maps:update_with(Type, fun(Qs0) -> [Q | Qs0] end, [Q], Acc)
               end, #{}, Qs),
    %% 对每种类型批量调用 deliver
    maps:fold(
      fun(Type, TypeQs, {Acc, QSAcc}) ->
          QS = get_queue_state(Type, QSAcc),
          Deliveries = [{Q, Delivery, Options} || Q <- TypeQs],
          case Type:deliver(Deliveries, QS) of
              {ok, QS1, Actions} ->
                  {Acc ++ Actions, update_queue_state(Type, QS1, QSAcc)};
              {error, Reason} ->
                  rabbit_log:warning("Queue type ~p deliver error: ~p", [Type, Reason]),
                  {Acc, QSAcc}
          end
      end, {[], QueueStates}, ByType).
```

分组投递的好处是减少函数调用次数：如果一条消息路由到 10 个同类型队列，只需调用一次 `Type:deliver`，而不是 10 次。对于 Quorum Queue 这类有 Raft 开销的类型，批量处理尤其重要。

`QueueStates` 是一个 map，key 为队列类型模块名，value 为该类型的聚合状态。channel 进程在整个生命周期内维护这个 map，每次调用 `rabbit_queue_type` 接口后用返回的新状态替换旧状态。

### 10.3 consume 与 cancel：消费者注册的多态路径

```erlang
consume(Q, Spec, QueueStates) ->
    Type = amqqueue:get_type(Q),
    QS   = get_queue_state(Type, QueueStates),
    case Type:consume(Q, Spec, QS) of
        {ok, QS1, Actions} ->
            {ok, update_queue_state(Type, QS1, QueueStates), Actions};
        {error, _} = Err ->
            Err
    end.
```

`Spec` 包含消费者的所有参数：`consumer_tag`、`no_ack`、`exclusive`、`prefetch_count`、`args`（包括 `x-priority`、`x-cancel-on-ha-failover` 等）。不同队列类型对这些参数的处理方式不同：经典队列把消费者注册到 `rabbit_queue_consumers`，仲裁队列通过 Raft 命令把消费者信息写入状态机，流队列则创建一个独立的 offset 追踪器。

`cancel` 的路径类似，但需要额外处理 `x-cancel-on-ha-failover` 语义：当队列发生 HA 故障转移时，设置了这个参数的消费者会收到 `basic.cancel` 通知，而不是静默地切换到新 master。

### 10.4 handle_event：异步事件的统一入口

队列进程（或 Ra 状态机）产生的异步事件通过 `handle_event` 回调通知 channel：

```erlang
handle_event(Q, Event, QueueStates) ->
    Type = amqqueue:get_type(Q),
    QS   = get_queue_state(Type, QueueStates),
    case Type:handle_event(Q, Event, QS) of
        {ok, QS1, Actions} ->
            {ok, update_queue_state(Type, QS1, QueueStates), Actions};
        {eol, Actions} ->
            %% 队列生命周期结束（被删除），清理状态
            {eol, remove_queue_state(Type, QueueStates), Actions};
        {error, Reason} ->
            {error, Reason}
    end.
```

常见的 Event 类型包括：`{down, Pid, Reason}`（队列进程崩溃）、`{queue_event, QName, Payload}`（队列内部事件，如 Quorum Queue 的 `{send_credit_reply, ...}`）、`{timeout, Ref}`（定时器触发）。`{eol, Actions}` 是特殊返回值，表示队列已被删除，channel 需要清理对应的消费者状态并向客户端发送 `basic.cancel`。

这个统一入口使得 channel 进程不需要区分事件来源，所有队列类型的异步通知都走同一条代码路径，大幅简化了 channel 的状态管理逻辑。

---

## 总结

通过十章的源码深挖，可以归纳出 RabbitMQ 几个贯穿始终的设计取舍：

**进程字典 vs ETS**：`credit_flow` 全部用进程字典，避免了 ETS 的原子操作开销和函数调用开销。进程字典是 O(1) 的本地哈希表，在高频路径（每条消息都调用）上选择它是正确的；而需要多进程共享的数据（queue/exchange 元数据、Trie 边）才用 ETS。

**同步 vs 异步写入**：小消息（<4KB）走 per-queue store 同步写入，confirm 路径简单；大消息走 shared msg_store 异步写入，confirm 路径复杂但支持去重。这个分流不是非此即彼，而是根据消息大小自动决定，兼顾了小消息吞吐和大消息资源效率。

**Trie GC 的 ChildCount**：删除 binding 后的节点回收需要判断节点是否为空，`ChildCount` 字段使这个判断从 O(N) 扫描降到 O(1)，以增加写入时的维护成本换取删除时的 GC 效率。这是典型的空间换时间。

**Raft 状态机的 Effect 机制**：Quorum Queue 把副作用（发消息给消费者、监控进程）分离为 `Effects` 列表，只在 leader apply 时执行，follower 跳过。这是 Raft 状态机实现里最难正确处理的部分，Ra 的 Effect 抽象把这个复杂性封装得非常清晰。

**deferred grant 的背压完整性**：`credit_flow` 的 `grant` 在自身被阻塞时不立即发送，而是缓存到 `credit_deferred`。这个细节保证了背压链的完整性——中游堵塞时不向上游放行，防止数据在中间节点无限积压。没有这个机制，背压链会在中间节点断裂，失去流控效果。

**快路径 vs 慢路径的消息投递**：`deliver_or_enqueue` 先尝试 `attempt_delivery` 直接推给消费者，成功则完全绕过 `backing_queue`，是真正的零存储路径；只有无消费者时才落盘。这个分支不是优化技巧，而是正确性要求——消息已经在消费者手里就不应该再写磁盘，否则会造成重复投递。

**双层背压的职责分离**：`unsent_message_count` 和 `rabbit_limiter` 是两个独立的阻塞条件，前者是队列进程对 channel 整体的保护（防止 channel 邮箱积压），后者是消费者对自身处理能力的声明（`prefetch_count`）。两者共同作用，但职责不重叠：即使消费者没有设置 `prefetch_count`，`unsent_message_count` 也能防止队列无限推送；即使 `unsent_message_count` 未超限，`prefetch_count` 也能精确控制飞行中消息数量。

**queue_type 的 Actions 模式**：`rabbit_queue_type` 的所有回调都返回 `{ok, QState, Actions}`，把状态变更和副作用分离。这使得队列类型的核心逻辑可以写成纯函数，便于测试和推理；副作用（发送 AMQP 帧、触发监控事件）统一在 channel 层执行，避免了队列类型直接依赖 channel 进程的 PID，降低了模块间耦合。

---

## 附录：一条消息的完整生命周期——从 TCP 连接到消费者 ack

前面十章逐模块深挖了 RabbitMQ 的内部机制。现在用一个具体的场景，把所有机制串成一条完整的时间线，让你看清每一行源码在这个过程里扮演的角色。

### 场景设定

- 一个 Java 生产者客户端，通过 AMQP 0-9-1 协议连接 RabbitMQ
- 交换机：`orders`，类型 `direct`
- 队列：`order.created`，经典队列，持久化，`x-max-length=1000`，`overflow=drop_head`，`x-message-ttl=60000`
- binding：`orders` → `order.created`，routing key = `created`
- 消费者：一个 Java 消费者，`prefetch_count=10`，`auto_ack=false`
- 消息：一条订单创建事件，payload 约 512 字节，开启 publisher confirm

整个过程涉及的进程（每个都是独立的 Erlang 进程）：

```
[TCP Socket]
    │
[rabbit_reader]          ← 负责读取和解析 TCP 字节流
    │
[rabbit_channel]         ← 每个 AMQP channel 一个进程，处理 AMQP 方法帧
    │
[rabbit_amqqueue_process]← 每个经典队列一个进程，管理消息存储和消费者调度
    │
[rabbit_variable_queue]  ← 后端存储，内嵌在队列进程里（非独立进程）
    │
[rabbit_msg_store]       ← 共享消息存储（持久化大消息），独立进程
```

---

### 阶段一：TCP 连接与 AMQP 握手

**Step 1.1 — TCP accept**

客户端发起 TCP 连接。RabbitMQ 的 acceptor 进程（由 `ranch` 监听）接受连接，为这个连接创建一个新的 `rabbit_reader` 进程。此后这条 TCP 连接的所有字节流都由这个 `rabbit_reader` 进程独占处理。

**Step 1.2 — AMQP 协议头协商**

客户端发送 `AMQP\0\0\9\1`（协议头），`rabbit_reader` 验证后回复 `connection.start`，携带服务端支持的认证机制（`PLAIN`、`AMQPLAIN`）和服务端属性。客户端回复 `connection.start-ok`，携带用户名密码（PLAIN 编码）。`rabbit_reader` 调用 `rabbit_access_control:check_user_pass_login` 验证身份，成功后进入 `connection.tune` 阶段协商 `frame_max`（帧最大字节数，默认 131072）和 `heartbeat`（心跳间隔，默认 60s）。

**Step 1.3 — connection.open**

客户端发送 `connection.open`，指定 vhost（`/`）。`rabbit_reader` 调用 `rabbit_access_control:check_vhost_access` 验证权限，成功后回复 `connection.open-ok`。至此 TCP 连接升级为一个已认证的 AMQP 连接，`rabbit_reader` 进入稳定的帧读取循环。

**Step 1.4 — channel.open：创建 rabbit_channel 进程**

客户端发送 `channel.open`（channel_number=1）。`rabbit_reader` 收到后调用 `rabbit_channel_sup:start_channel`，为这个 channel 创建一个新的 `rabbit_channel` 进程，并把 `{channel_number=1, ChPid}` 记录在自己的 channel table 里。回复客户端 `channel.open-ok`。

此时进程树里多了一个 `rabbit_channel` 进程。它的初始状态包含：

```erlang
#ch{
    state            = running,
    channel          = 1,
    reader_pid       = ReaderPid,        %% 对应的 reader 进程
    limiter          = rabbit_limiter:new(self()),  %% prefetch 控制器，初始无限制
    unconfirmed      = #{},              %% MsgSeqNo -> 待 confirm 的消息
    queue_states     = rabbit_queue_type:new(),     %% 队列类型状态 map
    ...
}
```

`credit_flow` 此时也在 `rabbit_channel` 进程的进程字典里初始化：`rabbit_reader` 进程和 `rabbit_channel` 进程之间建立了一个 credit 流，`rabbit_reader` 默认获得 400 条消息的 credit（由 `?INITIAL_CREDIT` 宏定义），每处理一条消息消耗一个 credit，`rabbit_channel` 每处理 200 条回赠一次。这条 credit 链是第二章讲的背压机制的起点。

---

### 阶段二：消费者注册

消费者客户端（独立进程，有自己的 `rabbit_reader` + `rabbit_channel`）发送 `basic.consume`：

```
basic.consume {
    queue           = "order.created",
    consumer_tag    = "consumer-1",
    no_ack          = false,          ← 需要显式 ack
    exclusive       = false,
    arguments       = {"x-priority": 0}
}
```

**Step 2.1 — rabbit_channel 处理 basic.consume**

消费者的 `rabbit_channel` 进程收到这个方法帧后，先做权限检查（`rabbit_access_control:check_read_permitted`），然后向队列进程发起注册：

```erlang
rabbit_queue_type:consume(Q, ConsumeSpec, QueueStates)
```

`ConsumeSpec` 包含了 `consumer_tag`、`no_ack=false`、`prefetch_count=10`、`x-priority=0` 等所有参数。`rabbit_queue_type:consume` 按队列类型分发，经典队列走 `rabbit_classic_queue:consume`，最终向队列进程（`rabbit_amqqueue_process`）发送一条 `{basic_consume, ...}` 消息。

**Step 2.2 — rabbit_amqqueue_process 注册消费者**

队列进程处理 `basic_consume`，调用 `rabbit_queue_consumers:add`：

```erlang
add(ChPid, ConsumerTag, NoAck, LimiterPid, LimiterActive, Prefetch,
    Args, IsEmpty, Consumers) ->
    %% 初始化或更新该 channel 的 #cr{} record
    CR = #cr{
        ch_pid               = ChPid,
        monitor              = erlang:monitor(process, ChPid),  ← 监控 channel 进程
        acktags              = sets:new(),
        consumer_count       = 1,
        unsent_message_count = 0,
        blocked_consumers    = priority_queue:new(),
        limiter              = rabbit_limiter:client(LimiterPid)
    },
    store_ch_record(CR),   ← 存入进程字典，key = {consumer_channel, ChPid}
    %% 把消费者加入 active_consumers 优先队列
    Consumer = #consumer{tag = ConsumerTag, ack_required = not NoAck,
                         prefetch_count = Prefetch, args = Args},
    add_consumer(Consumer, Consumers).
```

`erlang:monitor(process, ChPid)` 是关键一步：队列进程监控了消费者的 channel 进程。一旦 channel 进程崩溃（客户端断连），队列进程会收到 `{'DOWN', Ref, process, ChPid, Reason}` 消息，立刻把该 channel 所有未 ack 消息（存在 `acktags` 里的）重新入队。

**Step 2.3 — prefetch_count 生效：rabbit_limiter 初始化**

`prefetch_count=10` 被写入 `rabbit_limiter` 的状态。`rabbit_limiter` 维护一个 `credit` 计数器，初始值为 10。每向该消费者推送一条消息，credit 减一；每收到一条 ack，credit 加一。当 credit 降到 0 时，`rabbit_limiter:is_suspended` 返回 `true`，`is_ch_blocked` 的第二个条件触发，消费者进入 `blocked_consumers`，队列停止向它推送。

**Step 2.4 — basic.consume-ok**

队列进程处理完成后，`rabbit_channel` 回复消费者客户端 `basic.consume-ok`，携带 `consumer_tag="consumer-1"`。消费者进入等待状态。

此时队列进程的 `#q.consumers` 字段里已经有了这个消费者，`active_consumers` 优先队列里有一条记录：`{priority=0, {ChPid, Consumer}}`。

---

### 阶段三：生产者发送消息

生产者客户端（另一个 `rabbit_reader` + `rabbit_channel`，channel_number=1）先发送 `confirm.select`，开启 publisher confirm 模式，然后发布消息。

**Step 3.1 — confirm.select 开启 confirm 模式**

`rabbit_channel` 收到 `confirm.select` 后，设置 `confirm_enabled=true`，之后每条发布的消息都会分配一个单调递增的 `delivery_tag`（即 `MsgSeqNo`），从 1 开始。

**Step 3.2 — basic.publish 帧到达 rabbit_reader**

生产者发送：

```
basic.publish {
    exchange    = "orders",
    routing_key = "created",
    mandatory   = false
}
+ 消息头帧（content-header，携带 delivery_mode=2 即持久化、content_type 等属性）
+ 消息体帧（content-body，512 字节的 payload）
```

`rabbit_reader` 的 TCP 读取循环每次从 socket 读出一个完整的 AMQP 帧（帧头 7 字节 + payload + 帧尾标志 `\xCE`），按帧类型分发：

- `type=1`（method frame）→ 解析成 AMQP 方法，发给 `rabbit_channel` 进程
- `type=2`（content-header frame）→ 发给 `rabbit_channel` 进程，记录消息属性和总字节数
- `type=3`（content-body frame）→ 发给 `rabbit_channel` 进程，追加消息体

**每次向 `rabbit_channel` 发消息前，`rabbit_reader` 都调用 `credit_flow:send(ChPid)`**，消耗一个 credit。当 credit 降为 0 时，`rabbit_reader` 进程被 `credit_flow` 挂起（通过 `receive` 等待 grant 消息），停止读取 socket，客户端的 TCP 发送缓冲区因此填满，最终触发客户端侧的 TCP 背压。这就是第二章描述的背压从队列一路传导到 TCP 层的完整路径。

**Step 3.3 — rabbit_channel 组装消息**

`rabbit_channel` 收到 method frame + content-header + content-body 后，调用 `rabbit_channel:handle_method`，组装成一个 `#basic_message{}` record，分配 `MsgSeqNo=1`，记录到 `unconfirmed` map：

```erlang
unconfirmed = #{1 => #confirms{queue_names = [<<"order.created">>], ...}}
```

然后构造 `#delivery{}` record：

```erlang
Delivery = #delivery{
    mandatory = false,
    confirm   = true,          ← 需要 confirm
    sender    = self(),        ← 生产者 channel 进程 PID
    message   = #basic_message{
        exchange_name = <<"orders">>,
        routing_keys  = [<<"created">>],
        content       = #content{payload_fragments_rev = [<<512 bytes>>],
                                 properties = #'P_basic'{delivery_mode=2, ...}}
    },
    msg_seq_no = 1
}
```

**Step 3.4 — 路由：rabbit_exchange 查 Trie**

`rabbit_channel` 调用 `rabbit_exchange:route(Exchange, Delivery)`，进入第三章描述的 Trie 路由逻辑：

```erlang
rabbit_router:match_bindings(X, fun(#binding{key = Key}) ->
    %% 从 ETS 表 rabbit_route 里查 direct exchange 的精确匹配
    ets:lookup(rabbit_route, {resource(VHost, exchange, <<"orders">>), <<"created">>})
end)
```

对于 `direct` 类型交换机，`rabbit_exchange_type_direct:route` 直接在 ETS 里按 `{ExchangeName, RoutingKey}` 做精确查找，返回 `[<<"order.created">>]`，即匹配到一个队列。这个 ETS 查找是 O(1)，全程无锁（ETS 的并发读是安全的）。

如果是 `topic` 类型交换机，这里就会走第三章的 Trie 遍历——从 ETS 里按逐段匹配 `*` 和 `#` 通配符，时间复杂度取决于 Trie 深度，通常也在微秒级。

路由完成，`rabbit_channel` 拿到目标队列列表：`[QueuePid_of_order.created]`，通过 `rabbit_queue_type:deliver` 把消息投递出去。

---

### 阶段四：消息进入队列进程

**Step 4.1 — rabbit_queue_type:deliver → rabbit_amqqueue_process**

`rabbit_queue_type:deliver` 调用 `deliver0`，按队列类型分组（这里只有一个经典队列），调用 `rabbit_classic_queue:deliver`，最终向队列进程发送一条 `{deliver, Delivery, Flow}` 消息（Erlang 进程间消息，异步）。`Flow=true` 表示这条消息走 credit_flow 管控——发送前 `rabbit_channel` 调用 `credit_flow:send(QPid)` 消耗对队列进程的一个 credit。这是第二章背压链的第二段：`rabbit_channel` → `rabbit_amqqueue_process`。

**Step 4.2 — deliver_or_enqueue：第一个决策点**

队列进程的 `handle_info` 收到 `{deliver, Delivery, Flow}` 后，先调用 `credit_flow:ack(ChPid)` 向 `rabbit_channel` 回赠 credit（表示队列进程已经处理了这条消息），然后进入 `deliver_or_enqueue`：

```erlang
%% 队列进程内部执行路径：
deliver_or_enqueue(Delivery, _Delivered=false, State)
  └─ send_mandatory_or_confirm(Delivery, State)
  │    %% confirm=true，把 {ChPid=生产者channel, MsgSeqNo=1} 记录到 msg_id_to_channel
  │    msg_id_to_channel = gb_trees:insert(MsgId, {ProducerChPid, 1}, MTC)
  │
  └─ attempt_delivery(Delivery, false, State)
       └─ rabbit_queue_consumers:deliver(FetchFun, Delivery, Consumers)
            └─ priority_queue:out(active_consumers)
                 %% 取出消费者 {priority=0, {ConsumerChPid, Consumer}}
                 └─ is_ch_blocked(CR) → false   %% unsent_message_count=0 < 200，limiter credit=10 > 0
                 └─ FetchFun(AckRequired=true)
                      %% no_ack=false，需要写入 backing_queue 获得 AckTag
                      └─ BQ:publish_delivered(Message, MsgProps, BQS)
```

这里走的是第八章讲的"有消费者在线"的快路径——`attempt_delivery` 成功，消息直接推向消费者，但因为 `no_ack=false`，必须先调用 `BQ:publish_delivered` 把消息写入后端存储并取得 `AckTag`，这样才能在消费者 nack 时找回这条消息。

**Step 4.3 — backing_queue 写入：publish_delivered**

`BQ:publish_delivered` 进入 `rabbit_variable_queue`（第五章内容）。消息 512 字节，小于 4KB 的阈值，走 **per-queue store（rabbit_classic_queue_store）同步写入路径**：

```erlang
%% rabbit_variable_queue:publish_delivered 内部：
%% 1. 消息体写入 per-queue store
{MsgLocation, StoreState1} = rabbit_classic_queue_store:write(MsgId, Msg, StoreState),
%% 2. 在内存中维护一条队列索引记录（内存 segment）
%% 3. 如果队列是持久化的（durable=true），同步写入磁盘索引文件
IndexState1 = rabbit_queue_index:publish(MsgId, SeqId, MsgLocation, MsgProps, true, IndexState),
%% 4. 返回 AckTag = SeqId（序列号，用于后续定位这条消息）
{SeqId, BQS1}
```

因为是持久化消息（`delivery_mode=2`），`rabbit_queue_index:publish` 会把索引记录刷到磁盘（`file:sync`）。这是写入路径里最慢的一步，也是 confirm 延迟的主要来源。写完之后 `AckTag=SeqId` 返回给 `rabbit_queue_consumers`。

注意：如果消息超过 4KB，会走 `rabbit_msg_store` 异步写入路径（第五章的 `msg_store_write`），confirm 要等到 `msg_store` 的 sync 回调触发后才能发出。512 字节的消息不走这条路。

**Step 4.4 — 溢出检查被跳过**

因为 `attempt_delivery` 返回了 `{delivered, ...}`，`deliver_or_enqueue` 走快路径，**`maybe_drop_head` 不会被调用**——溢出检查只在消息真正入队（无消费者）时才执行。这条消息直接到了消费者手里，从未进入队列的存储结构，`x-max-length=1000` 的限制对它没有任何影响。

---

### 阶段五：消息推送到消费者

**Step 5.1 — deliver_to_consumer：发送 basic.deliver**

回到 `rabbit_queue_consumers:deliver_to_consumer`：

```erlang
deliver_to_consumer(Msg, AckTag=SeqId_42, ConsumerChPid, Consumer, CR, Consumers) ->
    %% 向消费者的 rabbit_channel 进程发送消息
    ok = rabbit_channel:deliver(ConsumerChPid, Consumer, Msg),
    %% 更新 #cr{} 状态
    CR1 = CR#cr{
        acktags              = sets:add_element(SeqId_42, CR#cr.acktags),
        %% unsent_message_count: 0 → 1
        unsent_message_count = 1
    },
    store_ch_record(CR1),
    %% maybe_block：1 < 200，且 limiter credit = 10 - 1 = 9 > 0，不阻塞
    {delivered, SeqId_42, Consumers1}.
```

**Step 5.2 — 消费者 rabbit_channel 组装 basic.deliver 帧**

消费者的 `rabbit_channel` 进程收到 `deliver` 消息后，组装 `basic.deliver` 方法帧：

```
basic.deliver {
    consumer_tag  = "consumer-1",
    delivery_tag  = 1,            ← 消费者侧的 delivery_tag，从 1 开始单调递增
    redelivered   = false,
    exchange      = "orders",
    routing_key   = "created"
}
+ content-header（消息属性）
+ content-body（512 字节 payload）
```

这几个帧通过 `rabbit_writer` 写入 TCP socket，经网络传输到消费者客户端。消费者客户端的应用代码在 `handleDelivery` 回调里收到消息，处理业务逻辑。

**Step 5.3 — limiter 扣减与阻塞检查**

`rabbit_channel:deliver` 调用前，`rabbit_limiter` 扣减一个 credit（10 → 9）。如果生产者继续快速发送消息，当第 10 条消息被推出后，limiter credit 降到 0，`is_ch_blocked` 的第二个条件成立，消费者进入 `blocked_consumers`。队列进程在下一次 `run_message_queue` 时发现无可用消费者，停止推送，消息开始在 `backing_queue` 里积压。这是 `prefetch_count` 的精确流控效果。

---

### 阶段六：消费者 ack 与 publisher confirm 收尾

**Step 6.1 — 消费者发送 basic.ack**

消费者业务代码处理完消息，调用 SDK 发送：

```
basic.ack {
    delivery_tag = 1,
    multiple     = false
}
```

消费者的 `rabbit_reader` 收到帧，发给 `rabbit_channel`。`rabbit_channel` 根据 `delivery_tag=1` 找到对应的 `{AckTag=SeqId_42, QPid}` 记录（channel 内部维护了 `delivery_tag → {QPid, AckTag}` 的映射），然后向队列进程发送 `{ack, [SeqId_42], ConsumerChPid}` 消息。

**Step 6.2 — rabbit_amqqueue_process 处理 ack**

队列进程调用 `rabbit_queue_consumers:subtract_acks`：

```erlang
subtract_acks(ConsumerChPid, [SeqId_42], Consumers, PossiblyUnblockFun) ->
    CR = ch_record(ConsumerChPid),
    %% 从 acktags 集合中移除 SeqId_42
    CR1 = CR#cr{
        acktags              = sets:del_element(SeqId_42, CR#cr.acktags),
        %% unsent_message_count: 1 → 0
        unsent_message_count = 0
    },
    %% 通知 limiter 释放一个 credit（9 → 10）
    ok = rabbit_limiter:ack(CR#cr.limiter, 1),
    store_ch_record(CR1),
    PossiblyUnblockFun(CR1).   %% 回调 possibly_unblock
```

**Step 6.3 — possibly_unblock：检查是否需要解锁**

`possibly_unblock` 调用 `is_ch_blocked(CR1)`：`unsent_message_count=0 < 200`，limiter credit=10 > 0，两个条件都不满足，返回 `false`。因此 `unblock` 不需要执行，`blocked_consumers` 本来就是空的，消费者一直在 `active_consumers` 里，无需任何操作。

如果之前因为 `prefetch_count` 耗尽而阻塞过，这里 `is_ch_blocked` 返回 `false`，`unblock` 会把 `blocked_consumers` 里的消费者逐一移回 `active_consumers`，然后队列进程调用 `run_message_queue`，继续推送积压消息。

**Step 6.4 — backing_queue ack：磁盘资源回收**

队列进程调用 `BQ:ack([SeqId_42], BQS)`，进入 `rabbit_variable_queue:ack`：

```erlang
%% 按 SeqId_42 找到磁盘上的索引记录
%% 删除 per-queue store 里的消息体文件记录
rabbit_classic_queue_store:delete(MsgLocation, StoreState),
%% 更新 queue_index，标记该消息为已 ack（后续 GC 时物理删除）
rabbit_queue_index:ack([SeqId_42], IndexState)
```

这一步把磁盘上的消息体和索引标记为可回收。实际的物理删除由 `rabbit_queue_index` 的 segment 文件 GC 机制完成——当一个 segment 里所有消息都被 ack 后，整个 segment 文件被删除，释放磁盘空间。

**Step 6.5 — publisher confirm 发出**

`BQ:ack` 完成后，`backing_queue` 返回已确认的 `MsgId` 列表。队列进程调用 `confirm_messages([MsgId], State)`：

```erlang
confirm_messages([MsgId], State = #q{msg_id_to_channel = MTC}) ->
    %% 从 gb_tree 里查出 {ProducerChPid, MsgSeqNo=1}
    {value, {ProducerChPid, 1}} = gb_trees:lookup(MsgId, MTC),
    %% 向生产者 channel 进程发送 confirm
    rabbit_misc:confirm_to_sender(ProducerChPid, [1]),
    State#q{msg_id_to_channel = gb_trees:delete(MsgId, MTC)}.
```

生产者的 `rabbit_channel` 收到 confirm 通知后，从 `unconfirmed` map 里删除 `MsgSeqNo=1`，然后组装 `basic.ack` 帧发回给生产者客户端：

```
basic.ack {
    delivery_tag = 1,
    multiple     = false
}
```

生产者 SDK 收到这个帧，触发 confirm 回调，生产者知道消息已经持久化落盘，可以安全继续发送下一条。

---

### 完整调用链一览

把以上六个阶段压缩成一张调用序列图，每一行对应一次进程间消息或函数调用：

```
生产者客户端
  │  basic.publish（TCP 帧）
  ▼
rabbit_reader（生产者侧）
  │  credit_flow:send(ChPid)                ← 消耗 reader→channel credit
  │  gen_server2:cast(ChPid, {method, ...}) ← 异步发给 channel
  ▼
rabbit_channel（生产者侧）
  │  credit_flow:ack(ReaderPid)             ← 归还 reader→channel credit
  │  rabbit_exchange:route(X, Delivery)     ← ETS Trie 查路由，O(1)
  │  rabbit_queue_type:deliver(Qs, ...)     ← deliver0 按类型分组
  │  credit_flow:send(QPid)                 ← 消耗 channel→queue credit
  │  QPid ! {deliver, Delivery, Flow}       ← 异步发给队列进程
  ▼
rabbit_amqqueue_process（队列进程）
  │  credit_flow:ack(ChPid)                 ← 归还 channel→queue credit
  │  deliver_or_enqueue(Delivery, ...)
  │    send_mandatory_or_confirm(...)       ← 记录 MsgId→{ChPid,SeqNo=1} 到 gb_tree
  │    attempt_delivery(...)
  │      rabbit_queue_consumers:deliver()
  │        priority_queue:out(active_consumers)  ← 选出优先级最高消费者
  │        is_ch_blocked(CR) → false             ← 双条件检查通过
  │        FetchFun(AckRequired=true)
  │          BQ:publish_delivered(Msg,...)   ← per-queue store 同步写入磁盘
  │          返回 AckTag=SeqId_42
  │        deliver_to_consumer(...)
  │          rabbit_channel:deliver(ConsChPid, ...)  ← 发给消费者 channel
  │          CR.acktags ∪= {SeqId_42}              ← 记录未 ack 的 AckTag
  │          CR.unsent_message_count = 1            ← 背压计数 +1
  ▼
rabbit_channel（消费者侧）
  │  组装 basic.deliver 帧 + content-header + content-body
  │  rabbit_writer:send_command(...)         ← 写入 TCP socket
  ▼
消费者客户端
  │  handleDelivery() 业务处理
  │  basic.ack {delivery_tag=1}（TCP 帧）
  ▼
rabbit_reader（消费者侧）
  │  gen_server2:cast(ConsChPid, {method, basic_ack, ...})
  ▼
rabbit_channel（消费者侧）
  │  ConsChPid → QPid ! {ack, [SeqId_42], ...}
  ▼
rabbit_amqqueue_process（队列进程）
  │  rabbit_queue_consumers:subtract_acks(...)
  │    CR.acktags ∖= {SeqId_42}             ← 移除已 ack
  │    CR.unsent_message_count = 0          ← 背压计数 -1
  │    rabbit_limiter:ack(limiter, 1)       ← prefetch credit +1（9→10）
  │    possibly_unblock(...)                ← is_ch_blocked=false，无需解锁
  │  BQ:ack([SeqId_42], BQS)               ← per-queue store 删除消息体
  │    rabbit_queue_index:ack(...)          ← 磁盘索引标记为可 GC
  │  confirm_messages([MsgId], State)
  │    gb_trees:lookup(MsgId, MTC)          ← 找到 {ProducerChPid, SeqNo=1}
  │    rabbit_misc:confirm_to_sender(...)   ← 向生产者 channel 发 confirm
  ▼
rabbit_channel（生产者侧）
  │  unconfirmed map 删除 SeqNo=1
  │  组装 basic.ack {delivery_tag=1} 帧
  │  rabbit_writer:send_command(...)
  ▼
生产者客户端
   confirm 回调触发，消息生命周期结束 ✓
```

整条链路跨越了 6 个独立的 Erlang 进程，涉及 2 次磁盘写入（per-queue store 写入 + queue_index 写入），1 次磁盘标记（queue_index ack），以及贯穿全程的 credit_flow 背压链。在消费者在线、消息小于 4KB 的典型场景下，消息从生产者发出到消费者收到，全程没有经过任何队列内存结构的存储，`backing_queue` 只是作为 ack 所需的"凭证存档"而存在。这就是 RabbitMQ 在正常负载下能保持低延迟的根本原因。
