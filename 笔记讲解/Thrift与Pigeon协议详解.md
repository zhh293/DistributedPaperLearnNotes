# Mario 框架中 Thrift 与 Pigeon 协议详解

---

## 一、Mario 怎么用这两个协议

### 核心思路：注解驱动 + 泛化调用

传统测试 RPC 接口的方式，需要把被测服务的 Jar 包引入到测试项目里，才能拿到接口定义和数据结构。被测服务一升级，测试项目的依赖就要跟着改，非常麻烦。

Mario 的解法叫**泛化调用（Generic Invoke）**：完全不引入被测服务的 Jar，直接用字符串描述接口名、方法名、参数，框架在运行时动态发起 RPC 调用，参数和返回值都用 JSON 字符串传递。

使用方式是**注解驱动**：你在测试类和测试方法上打注解，声明"要调哪个服务、哪个方法"，然后调一行 `ThriftProcessor.invoke(request)` 或 `PigeonProcessor.invoke(params)`，剩下的全由框架完成。

---

### Thrift 的用法

在测试类上打 `@ThriftAPI` 声明服务信息，在测试方法上打 `@ThriftAPI` 声明方法名，然后调 `ThriftProcessor.invoke()` 发起调用：

```java
@ThriftAPI(
    appkey = "com.sankuai.hotel.dm.wechatmsg",
    interfaceName = "com.sankuai.hotel.dm.wechatmsg.api.service.WxAppTemplateMsgAdminRPCService"
)
public class WechatMsgServiceTest {

    @ThriftAPI(methodName = "getTemplateMsgConfig")
    @Test
    public void testGetTemplateMsgConfig(JSONObject request, JSONObject expect) throws Exception {
        String response = ThriftProcessor.invoke(request); // 传入参数，拿回 JSON 响应
        AssertUtil.assertJsonEquals(response, expect, JSONCompareMode.STRICT, null);
    }
}
```

`@ThriftAPI` 的核心参数：

```java
public @interface ThriftAPI {
    String appkey()        default ""; // 被测服务的唯一标识（必填）
    String interfaceName() default ""; // 接口全限定类名（必填）
    String methodName()    default ""; // 要调用的方法名（方法级注解填）
    int    timeout()       default 0;  // 超时毫秒，0 表示用全局配置
    String flowTag()       default ""; // 泳道标识，用于打到指定测试环境
}
```

---

### Pigeon 的用法

Pigeon 的用法结构完全一样，只是注解换成 `@PigeonAPI`，入口换成 `PigeonProcessor.invoke()`：

```java
@PigeonAPI(
    url = "http://service.dianping.com/receiptQueryService/receiptQueryService_1.0.0",
    methodName = "getReceipt",
    paramTypes = {"java.lang.Long", "java.lang.Long"} // Pigeon 必须显式声明参数类型
)
@Test
public void testGetReceipt() throws Exception {
    String response = PigeonProcessor.invoke(859763576L, 1300034152L);
    AssertUtil.assertNotNull(response);
}
```

和 Thrift 有两个关键差异：Pigeon 用 `url` 标识服务（而不是 appkey + interfaceName），并且泛化调用时必须通过 `paramTypes` 显式声明每个参数的类型，Thrift 不需要。原因在后面协议原理部分解释。

---

## 二、完整调用链与每一步原理

以 Thrift 为例，从你写的一行 `ThriftProcessor.invoke(request)` 开始，到服务端返回响应，完整经历以下几个阶段。先看整体调用关系图，再逐步拆解每一步：

```
测试方法
  └─调用─▶ ThriftProcessor.invoke(Object... parms)          // 公开入口，可变参数
               ├─ [反射] new Throwable() 拿调用栈
               ├─ Class.forName() + getMethods() 读注解
               ├─ new GenericConfigModel(classThriftAPI)     // 用类级注解初始化配置
               ├─ genericConfigModel.update(methodThriftAPI) // 方法级注解覆盖
               ├─ getDirectIp()                              // 服务发现
               │     └─ OCTOUtil.getOnlineIP()
               │           └─ OCTOUtil.getOnlineIPList()
               │                 └─ OCTOApiService.getOnlineOCTOInfo()  // HTTP 调 OCTO
               ├─ [可选] ServerIpPortsParser.getThriftServerIpPorts()   // SWITCH_TO_DIRECT 模式
               ├─ JSONObject.toJSONString(param) × N         // 参数序列化为 JSON 字符串
               └─调用─▶ ThriftProcessor.invoke(GenericConfigModel)      // 重载，真正执行
                             ├─ buildGenericSerivce(genericConfigModel)  // 构建/复用代理
                             │     ├─ thriftClientProxyMap.get(cacheKey) // 查缓存
                             │     ├─ [未命中] new ThriftClientProxy()
                             │     │     ├─ setGeneric("json-simple")
                             │     │     ├─ setNettyIO(true)
                             │     │     └─ afterPropertiesSet()         // 建立 Netty 连接
                             │     ├─ buildTracer()                      // 初始化 Mtrace Span
                             │     │     └─ Tracer.serverRecv(param)
                             │     ├─ Tracer.putContext() × N            // 写入 trace 上下文
                             │     └─ Tracer.setSwimlane()               // 写入泳道
                             └─ GenericService.$invoke(method, null, params)  // 发起 RPC
                                   └─ [Thrift 内部，jar 包中]
                                         ├─ JSON → Thrift 二进制序列化
                                         ├─ 构造帧：4字节长度 + 消息体
                                         ├─ Netty channel.writeAndFlush()
                                         ├─ 业务线程 future.get() 阻塞
                                         ├─ IO 线程收响应，future.complete()
                                         └─ 返回 JSON 字符串
  ◀─返回─ ResponseRecorder.record(response)                  // 记录响应
  ◀─返回─ return response                                    // 回到测试方法
```

---

### 第一步：入口 `invoke(Object... parms)` — 反射读注解，组装配置

**调用者：** 你写的测试方法。**被调用者：** `ThriftProcessor.invoke(Object... parms)`（第 132 行）。

这个方法是整条链路的入口，接收可变参数（你传进来的请求对象）。它面临一个问题：它自己是个静态工具方法，完全不知道是谁调用了它，也就不知道该去哪里读 `@ThriftAPI` 注解。解决方案是**主动构造一个 `Throwable`，从它的调用栈里找到调用方**：

```java
// ThriftProcessor.java 第 132-200 行
public static String invoke(Object... parms) throws Exception {

    // ── 第一段：从调用栈找到调用方 ──────────────────────────────────────
    Throwable ex = new Throwable();
    // getStackTrace() 返回当前线程的完整调用栈，每个 StackTraceElement 代表一个栈帧
    // 栈帧[0] 是 new Throwable() 这一行，栈帧[1] 是 invoke() 本身，
    // 栈帧[2] 才是调用 invoke() 的那个测试方法
    StackTraceElement[] stackElements = ex.getStackTrace();

    StackTraceElement stackTraceElement = null;
    for (int i = 0; i < stackElements.length; i++) {
        // 跳过所有属于 ThriftProcessor 自己的帧，找到第一个外部帧
        if (!stackElements[i].getClassName().equals(ThriftProcessor.class.getName())) {
            stackTraceElement = stackElements[i];
            break;
        }
    }
    // 此时 stackTraceElement.getClassName()  = "com.xxx.WechatMsgServiceTest"
    // 此时 stackTraceElement.getMethodName() = "testGetTemplateMsgConfig"

    // ── 第二段：反射加载调用方的类，遍历方法找注解 ──────────────────────
    String methodName = stackTraceElement.getMethodName();
    Class cls = Class.forName(stackTraceElement.getClassName()); // 加载测试类

    Method[] methods = cls.getMethods(); // 拿到所有 public 方法
    ThriftAPI methodThriftAPI = null;
    for (int i = 0; i < methods.length; i++) {
        if (methods[i].getName().equals(methodName)) {
            // 在同名方法里找有 @ThriftAPI 注解的那个
            methodThriftAPI = methods[i].getAnnotation(ThriftAPI.class);
            if (methodThriftAPI != null) {
                break; // 找到就停，不继续遍历
            }
        }
    }
    // 同时读类级别的 @ThriftAPI（提供 appkey 和 interfaceName）
    ThriftAPI classThriftAPI = (ThriftAPI) cls.getAnnotation(ThriftAPI.class);

    // ── 第三段：组装 GenericConfigModel ─────────────────────────────────
    // 先用类级注解初始化（提供 appkey、interfaceName、timeout 等公共配置）
    // GenericConfigModel 构造器会把 ThriftAPI 的每个字段逐一赋值
    GenericConfigModel genericConfigModel =
        classThriftAPI != null ? new GenericConfigModel(classThriftAPI) : new GenericConfigModel();

    // 再用方法级注解覆盖（提供 methodName，也可以覆盖 appkey、timeout 等）
    // update() 内部对每个字段判断 isNotBlank，只覆盖非空的字段
    if (methodThriftAPI != null && StringUtils.isNotBlank(methodThriftAPI.methodName())) {
        genericConfigModel.update(methodThriftAPI);
        // 校验三个必填字段：appkey、methodName、interfaceName 都不能为空
        if (StringUtils.isBlank(genericConfigModel.getRemoteAppKey())
                || StringUtils.isBlank(genericConfigModel.getMethodName())
                || StringUtils.isBlank(genericConfigModel.getServiceName())) {
            // 任何一个为空，直接抛 RPCAPIDefindException，测试失败
            throw new RPCAPIDefindException(...);
        }
    } else {
        // 方法上没有 @ThriftAPI 或者 methodName 为空，同样报错
        throw new RPCAPIDefindException(...);
    }
    // 走到这里，genericConfigModel 已经包含完整配置：
    // remoteAppKey = "com.sankuai.hotel.dm.wechatmsg"
    // serviceName  = "com.sankuai.hotel.dm.wechatmsg.api.service.WxAppTemplateMsgAdminRPCService"
    // methodName   = "getTemplateMsgConfig"
    // timeout      = 3000（毫秒）
    // localAppkey  = "com.sankuai.toolchain.mario"
```

`GenericConfigModel` 是整条链路的数据载体，它的 `update(ThriftAPI)` 方法（`GenericConfigModel.java` 第 65-86 行）对每个字段都做了 `isNotBlank` 判断，只有方法级注解里非空的字段才会覆盖类级注解的值，这样类级注解就起到了"公共默认值"的作用。

---

### 第二步：`getDirectIp()` — 服务发现，找到目标机器 IP

**调用者：** `invoke(Object... parms)`（第 176 行）。**被调用者：** `ThriftProcessor.getDirectIp()` → `OCTOUtil.getOnlineIP()` → `OCTOUtil.getOnlineIPList()` → `OCTOApiService.getOnlineOCTOInfo()`。

```java
// ThriftProcessor.java 第 176-190 行
// 调用 getDirectIp()，传入 appkey 和 flowTag（泳道标识）
String directIp = getDirectIp(genericConfigModel.getRemoteAppKey(), genericConfigModel.getLiteSET());

// 如果拿到了合法 IP（通过正则校验），就把 "IP:port" 写入 genericConfigModel
if (StringUtils.isNotBlank(directIp) && ComputerUtil.isIP(directIp)) {
    // ComputerUtil.isIP() 用正则 \d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3} 校验格式
    // 并且每段数字必须在 0-255 之间
    genericConfigModel.setServerIpPorts(directIp + ":" + genericConfigModel.getPort());
}

// 另一个分支：如果配置了 SWITCH_TO_DIRECT=true，走 ServerIpPortsParser 直连
if (Boolean.parseBoolean(ConfigManage.getValue("SWITCH_TO_DIRECT"))) {
    String serverIpPorts = ServerIpPortsParser.getInstance()
        .getThriftServerIpPorts(genericConfigModel.getRemoteAppKey(), genericConfigModel.getServiceName());
    // ServerIpPortsParser 会查询 OCTO 测试环境接口，按 serviceName 精确匹配 IP:port
    if (StringUtils.isNotBlank(serverIpPorts)) {
        int port = Integer.parseInt(serverIpPorts.split(":")[1].trim());
        genericConfigModel.setServerIpPorts(serverIpPorts);
        genericConfigModel.setPort(port);
    }
}
```

`getDirectIp()` 内部有三条分支，优先级从高到低：

```java
// ThriftProcessor.java 第 308-326 行
public String getDirectIp(String appKey, String flowTag) {
    if (StringUtils.isEmpty(appKey)) {
        return ""; // appkey 为空直接返回，后续走 OCTO 自动发现
    }

    // 分支 1：注解上指定了 flowTag（泳道），去 OCTO 查这条泳道的机器
    // 场景：测试特定泳道的服务，如 flowTag="feature-123"
    if (StringUtils.isNotBlank(flowTag)) {
        return OCTOUtil.getOnlineIP(appKey, flowTag);
    }

    // 分支 2：在 FST 平台上执行，从 JVM 系统属性读泳道
    // FST 是美团的流量染色测试平台，执行时会注入 traffic_env 系统属性
    if (ConfigManage.isFstRunTest() && ConfigManage.isFstLiteSET()) {
        flowTag = System.getProperty("traffic_env"); // 从 JVM 参数读
        return OCTOUtil.getOnlineIP(appKey, flowTag);
    }

    // 分支 3：从配置文件读直连 IP（env.properties 里写 appkey=10.25.76.165）
    // ConfigManage.getValue() 最终调用 ContextConfigModel.getCustomProp()
    directIp = ConfigManage.getValue(appKey);
    return directIp;
    // 如果配置文件里也没有，返回空字符串，后续 ThriftClientProxy 走 OCTO 自动发现
}
```

当 `getDirectIp()` 返回空字符串时，`genericConfigModel.serverIpPorts` 不会被设置，`ThriftClientProxy` 在初始化时会自己去 OCTO 查询服务地址，这是最常见的情况。

`OCTOUtil.getOnlineIP()` 的完整调用链：

```java
// OCTOUtil.java 第 71-79 行
public static String getOnlineIP(String appKey, String set) {
    List<String> onlineIPList = getOnlineIPList(1, appKey, set); // type=1 表示 Thrift 服务
    if (onlineIPList.isEmpty()) {
        return ""; // 没查到，返回空
    }
    String ip = onlineIPList.get(0); // 取第一个，不做负载均衡（由 ThriftClientProxy 负责）
    log.info("当前set为: {}, ip为: {}", set, ip);
    return ip;
}

// OCTOUtil.java 第 15-37 行
public static List<String> getOnlineIPList(int type, String appKey, String set) {
    // 调用 OCTOApiService，发一个 HTTP GET 请求到 OCTO 接口
    Object onlineOCTOInfo = OCTOApi.getOnlineOCTOInfo(type, appKey);
    // onlineOCTOInfo 是 OCTO 返回的 JSON，结构是 {"data": [{"ip":"10.x.x.x","cell":"","port":9000}, ...]}

    if (set == null || set.isEmpty()) {
        // set 为空：用 JsonPath 筛选 cell=='' 的机器（中心链路，非泳道机器）
        ipPortList = JsonPath.read(onlineOCTOInfo, "$.data[?(@.cell=='')].ip");
    } else {
        // set 不为空：筛选 cell==set 的机器（指定泳道的机器）
        ipPortList = JsonPath.read(onlineOCTOInfo, "$.data[?(@.cell=='" + set + "')].ip");
    }
    return ipPortList;
}
```

`OCTOApiService.getOnlineOCTOInfo()` 是最底层的 HTTP 调用：

```java
// OCTOApiService.java 第 67-80 行
public Object getOnlineOCTOInfo(int type, String appKey) {
    // 拼接 OCTO 查询 URL，例如：
    // http://OCTO.sankuai.com/api/provider?status=2&pageNo=1&pageSize=3000&type=1&appkey=com.sankuai.hotel.dm.wechatmsg
    String url = baseUrl_online + type + "&appkey=" + appKey;

    // 用 Retrofit + OkHttp 发 HTTP GET 请求
    // OkHttpClient 和 Retrofit 实例都是单例（双重检查锁），全局复用
    Call<Object> call = getSharedOCTOService().getOCTOInfo(url);
    Response<Object> response = call.execute(); // 同步执行
    return response.body(); // 返回 Gson 反序列化后的 Object（实际是 LinkedTreeMap）
}
```

---

### 第三步：`buildGenericSerivce()` — 构建代理，建立 Netty 连接，注入 Mtrace

**调用者：** `invoke(GenericConfigModel)`（第 56 行）。**被调用者：** `buildGenericSerivce(GenericConfigModel)`（第 62 行）。

注意这里有两个重载的 `invoke`：第一个是公开入口 `invoke(Object... parms)`，它在组装好 `GenericConfigModel` 后，调用第二个 `invoke(GenericConfigModel)`（第 54 行），后者再调 `buildGenericSerivce()`。

```java
// ThriftProcessor.java 第 54-60 行（第二个 invoke，内部使用）
public static String invoke(GenericConfigModel genericConfigModel) throws Exception {
    ThriftClientProxy thriftClientProxy = buildGenericSerivce(genericConfigModel); // 构建代理
    // 拿到代理后直接发起泛化调用，返回 JSON 字符串
    String result = ((GenericService) thriftClientProxy.getObject())
        .$invoke(genericConfigModel.getMethodName(), null, genericConfigModel.getParameters());
    return result;
}
```

`buildGenericSerivce()` 是这条链路里最复杂的方法，做了三件事：管理连接缓存、初始化 Netty 连接、注入 Mtrace 上下文。

```java
// ThriftProcessor.java 第 62-114 行
private static ThriftClientProxy buildGenericSerivce(GenericConfigModel genericConfigModel) throws Exception {

    // ── 第一件事：连接缓存管理 ────────────────────────────────────────────
    // cacheKey 由 5 个字段拼成，唯一标识"同一个服务的同一种调用方式"
    // 格式：fanhua-{appkey}-{interfaceName}-{port}-{localAppkey}-{serverIpPorts}
    String cacheKey = String.format("fanhua-%s-%s-%s-%s-%s",
        genericConfigModel.getRemoteAppKey(),
        genericConfigModel.getServiceName(),
        genericConfigModel.getPort(),
        genericConfigModel.getLocalAppkey(),
        genericConfigModel.getServerIpPorts());

    // thriftClientProxyMap 是 ConcurrentHashMap，类级静态变量，整个 JVM 进程共享
    ThriftClientProxy clientProxy = thriftClientProxyMap.get(cacheKey);

    // 同时读取当前线程的 Mtrace 上下文（后面要合并写回）
    Map<String, String> MtraceContent = Tracer.getAllContext() == null
        ? new HashMap<>() : Tracer.getAllContext();

    if (clientProxy == null || clientProxy.isDestroyed()) {
        // ── 第二件事：缓存未命中，新建 ThriftClientProxy ──────────────────
        clientProxy = new ThriftClientProxy(); // Thrift 提供的客户端代理类

        clientProxy.setRemoteAppkey(genericConfigModel.getRemoteAppKey()); // 目标服务 appkey

        if (genericConfigModel.getPort() > 0) {
            // 有端口号，说明要直连（不走 OCTO 负载均衡）
            clientProxy.setRemoteServerPort(genericConfigModel.getPort());
            clientProxy.setFilterByServiceName(false); // 关闭按 serviceName 过滤
            if (StringUtils.isNotBlank(genericConfigModel.getServerIpPorts())) {
                // 有具体的 IP:port，设置直连地址
                clientProxy.setServerIpPorts(genericConfigModel.getServerIpPorts());
                clientProxy.setRemoteUniProto(true); // 开启单机直连模式
            }
        } else {
            // 没有端口号，走 OCTO 服务发现 + 负载均衡
            clientProxy.setFilterByServiceName(true);
        }

        clientProxy.setAppKey(genericConfigModel.getLocalAppkey()); // 调用方 appkey（Mario 自己）
        clientProxy.setTimeout(genericConfigModel.getTimeout());     // 超时时间（毫秒）
        clientProxy.setAsync(false);                                 // 同步调用
        clientProxy.setGenericServiceName(genericConfigModel.getServiceName()); // 接口全限定名
        clientProxy.setGeneric("json-simple"); // 关键：开启泛化调用，参数用 JSON 传递
        clientProxy.setNettyIO(true);          // 关键：使用 Netty NIO，而不是阻塞 IO

        // afterPropertiesSet() 是 Spring InitializingBean 接口的方法
        // Thrift 在这里完成所有初始化：
        //   1. 创建 NioEventLoopGroup（Netty IO 线程池）
        //   2. 创建 Bootstrap，配置 Pipeline（ThriftFrameDecoder + ThriftFrameEncoder + Handler）
        //   3. 如果有 serverIpPorts，直接建立 TCP 连接
        //   4. 如果没有，向 OCTO 注册监听，等第一次调用时再建连
        clientProxy.afterPropertiesSet();

        // 写入缓存，下次同一个服务直接复用，不重复建连
        thriftClientProxyMap.put(cacheKey, clientProxy);
    }
    // 走到这里，clientProxy 一定是可用的（新建或从缓存取出）

    // ── 第三件事：注入 Mtrace 链路追踪上下文 ─────────────────────────────
    // 如果当前线程还没有 traceId，调用 buildTracer() 新建一个 Span
    if (StringUtils.isBlank(Tracer.id())) {
        buildTracer(genericConfigModel);
    }

    // 从配置文件读取 TRACER. 前缀的自定义 KV，合并写入 Mtrace 上下文
    // 例如配置 TRACER.env=test，会把 env=test 写入链路追踪
    Map<String, String> tracerValues = ConfigManage.getCustomPropsByPrefix("TRACER");
    if (!tracerValues.isEmpty()) {
        for (Map.Entry<String, String> entry : tracerValues.entrySet()) {
            // 只写入 MtraceContent 里不存在的 key，不覆盖已有值
            if (null == MtraceContent.get(entry.getKey())) {
                MtraceContent.put(entry.getKey(), entry.getValue());
            }
        }
    }

    // 把合并后的 MtraceContent 全部写入当前线程的 Tracer 上下文
    // Tracer 是 ThreadLocal 的，每个线程独立，不会互相干扰
    if (MtraceContent != null) {
        MtraceContent.keySet().forEach(key -> Tracer.putContext(key, MtraceContent.get(key)));
    }

    // 如果配置了全局泳道（SWIMLANE），写入 Tracer
    // Thrift 发请求时会自动把 Tracer 里的泳道信息附加到请求头
    if (StringUtils.isNotBlank(ConfigManage.getSWIMLANE())) {
        Tracer.setSwimlane(ConfigManage.getSWIMLANE());
    }

    return clientProxy; // 返回可用的代理对象
}
```

`buildTracer()` 是新建 Mtrace Span 的地方：

```java
// ThriftProcessor.java 第 116-122 行
private static void buildTracer(GenericConfigModel genericConfigModel) {
    // spanName 格式：interfaceName.methodName，例如：
    // "com.sankuai.hotel.dm.wechatmsg.api.service.WxAppTemplateMsgAdminRPCService.getTemplateMsgConfig"
    String spanName = String.format("%s.%s",
        genericConfigModel.getServiceName(), genericConfigModel.getMethodName());

    TraceParam param = new TraceParam(spanName);
    param.setRemoteAppKey(genericConfigModel.getRemoteAppKey()); // 被调用方 appkey
    param.setLocalAppKey(genericConfigModel.getLocalAppkey());   // 调用方 appkey（Mario）

    // serverRecv() 表示"服务端收到请求"，这里用它来创建一个新的根 Span
    // 调用后 Tracer.id() 就不再为空，后续不会重复创建
    Tracer.serverRecv(param);
}
```

---

### 第四步：参数序列化，调用 `invoke(GenericConfigModel)`

**调用者：** `invoke(Object... parms)`（第 192-198 行）。**被调用者：** `invoke(GenericConfigModel)`（第 54 行）。

回到第一个 `invoke` 方法，在 `buildGenericSerivce` 之前，还有一步参数序列化：

```java
// ThriftProcessor.java 第 192-198 行
List<String> parameters = new ArrayList<>();
for (Object param : parms) {
    // 用 fastjson 把每个入参对象序列化成 JSON 字符串
    // 例如传入 JSONObject{"orderId":12345}，变成字符串 "{\"orderId\":12345}"
    // 例如传入 Long 12345L，变成字符串 "12345"
    parameters.add(JSONObject.toJSONString(param));
}
genericConfigModel.setParameters(parameters); // 写入配置模型

// 调用第二个 invoke 重载，传入完整的 GenericConfigModel
String response = invoke(genericConfigModel);

// 把响应记录到 ResponseRecorder（ThreadLocal 存储，每个测试线程独立）
ResponseRecorder.record(response);
return response;
```

---

### 第五步：`GenericService.$invoke()` — 发起泛化 RPC 调用

**调用者：** `invoke(GenericConfigModel)`（第 57 行）。**被调用者：** Thrift 的 `GenericService.$invoke()`（在 Thrift.jar 内部）。

```java
// ThriftProcessor.java 第 57-59 行
String result = ((GenericService) thriftClientProxy.getObject())
    .$invoke(
        genericConfigModel.getMethodName(),  // 方法名，如 "getTemplateMsgConfig"
        null,                                // 参数类型列表，null 表示让框架自动推断
        genericConfigModel.getParameters()   // JSON 字符串列表，如 ["{\"templateId\":1}"]
    );
```

`thriftClientProxy.getObject()` 返回的是 Thrift 生成的动态代理对象，它实现了 `GenericService` 接口。`$invoke()` 被调用后，控制权完全交给 Thrift 内部，Mario 项目的代码到这里就结束了。Thrift 内部的执行流程如下：

```
GenericService.$invoke() 内部（Thrift.jar，Mario 看不到源码）

① json-simple 序列化器接管
   把 JSON 字符串参数转成 Thrift TBinaryProtocol 二进制格式：
   - 每个字段用"字段编号(2字节) + 类型标识(1字节) + 值"编码
   - 不传字段名，只传编号，体积极小

② 构造完整的 Thrift 消息帧
   ┌──────────────┬──────────────────────────────────────────────────┐
   │ 帧长度(4字节) │ 版本+类型(4) | 方法名长度(4) | 方法名(N) |        │
   │              │ seqId(4) | 参数字段列表 | STOP(1)               │
   └──────────────┴──────────────────────────────────────────────────┘
   seqId 是自增序列号，用于后续匹配响应

③ 把 Mtrace 上下文（traceId、spanId、泳道）附加到请求头
   （Thrift 自动从 ThreadLocal 的 Tracer 里读取）

④ Netty channel.writeAndFlush(frame)
   把帧写入 NioSocketChannel 的发送缓冲区，立即返回（非阻塞）
   Netty IO 线程负责实际的 TCP 发送

⑤ 业务线程调用 future.get(timeout) 挂起
   底层是 LockSupport.parkNanos()，线程挂起不占 CPU
   同时把 seqId → future 的映射注册到 pendingRequests 表

⑥ 服务端收到帧，执行业务逻辑，返回响应帧

⑦ Netty IO 线程收到响应帧
   - ThriftFrameDecoder 提取完整帧（处理粘包/拆包）
   - 解析响应帧，取出 seqId
   - 从 pendingRequests 表找到对应的 future
   - future.complete(responseJson) 唤醒业务线程

⑧ 业务线程从 future.get() 返回，拿到 JSON 字符串响应
```

---

### 第六步：`ResponseRecorder.record()` — 记录响应

**调用者：** `invoke(Object... parms)`（第 198 行）。**被调用者：** `ResponseRecorder.record(String)`。

```java
// ResponseRecorder.java 第 31-39 行
public static void record(String response) {
    if (response == null) return;

    // m_responseMap 是 ThreadLocal<List<String>>
    // 每个测试线程有自己独立的响应列表，多线程并发执行时互不干扰
    List<String> responseMaps = m_responseMap.get();
    if (responseMaps == null) {
        responseMaps = new ArrayList<String>();
        m_responseMap.set(responseMaps);
    }

    // truncate() 把超过 10000 字符的响应截断，防止内存溢出
    responseMaps.add(truncate(response));
    // 记录完成后，这个 response 可以被 Mario 的报告模块读取，
    // 也可以被 Diff 测试模块拿来做对比
}
```

`ResponseRecorder` 的数据会在测试结束后被 Mario 的报告模块（`EagleReport`、`FocusReport`）读取，生成测试报告。在 Diff 测试场景下，两条链路的响应都会被记录，然后做 JSON 对比。

---

### 完整调用链总结

把所有方法的调用关系和所在文件整理成一张表：

| 调用顺序 | 方法 | 所在文件 | 入参 | 返回值 |
|---|---|---|---|---|
| 1 | `invoke(Object... parms)` | `ThriftProcessor.java:132` | 测试入参（可变参数） | JSON 字符串响应 |
| 2 | `getDirectIp(appKey, flowTag)` | `ThriftProcessor.java:308` | appkey、泳道 | IP 字符串或空 |
| 3 | `OCTOUtil.getOnlineIP(appKey, set)` | `OCTOUtil.java:71` | appkey、泳道 | IP 字符串 |
| 4 | `OCTOUtil.getOnlineIPList(type, appKey, set)` | `OCTOUtil.java:15` | 类型、appkey、泳道 | IP 列表 |
| 5 | `OCTOApiService.getOnlineOCTOInfo(type, appKey)` | `OCTOApiService.java:67` | 类型、appkey | OCTO 返回的 JSON Object |
| 6 | `invoke(GenericConfigModel)` | `ThriftProcessor.java:54` | 配置模型 | JSON 字符串响应 |
| 7 | `buildGenericSerivce(GenericConfigModel)` | `ThriftProcessor.java:62` | 配置模型 | ThriftClientProxy |
| 8 | `buildTracer(GenericConfigModel)` | `ThriftProcessor.java:116` | 配置模型 | void |
| 9 | `Tracer.serverRecv(param)` | Mtrace.jar | TraceParam | void（写入 ThreadLocal） |
| 10 | `GenericService.$invoke(method, null, params)` | Thrift.jar（不可见） | 方法名、参数 | JSON 字符串响应 |
| 11 | `ResponseRecorder.record(response)` | `ResponseRecorder.java:31` | JSON 字符串 | void（写入 ThreadLocal） |

---

## 三、Thrift 与 Pigeon 协议原理

### Thrift 原理

Thrift 要解决的核心问题是：两个进程之间怎么高效地传递结构化数据。HTTP + JSON 的方案太重——JSON 文本体积大、解析慢，HTTP 是短连接每次都要握手。Thrift 的解法是**二进制协议 + TCP 长连接**。

Thrift 要求服务提供方先写一个 `.thrift` 文件（IDL，接口描述语言），描述接口和数据结构：

```thrift
struct OrderInfo {
    1: required i64    orderId   // 字段编号 1
    2: required string status    // 字段编号 2
    3: optional double amount    // 字段编号 3
}

service IOrderService {
    OrderInfo queryOrder(1: i64 orderId)
}
```

然后用 Thrift 编译器把这个文件编译成 Java 代码，生成的代码包含完整的序列化逻辑。**字段编号是 Thrift 的核心设计**：序列化时不传字段名（"orderId" 这个字符串），只传编号（数字 1），接收方根据 IDL 里的编号映射来解析。这就是 Thrift 二进制体积极小的原因。

序列化后的数据加上一个帧头就构成一个完整的 Thrift 消息帧：

```
┌──────────────┬──────────────────────────────────────────────────┐
│  帧长度(4字节) │  消息体                                           │
│              │  版本+类型(4) | 方法名长度(4) | 方法名(N) |         │
│              │  序列号seqId(4) | 参数（字段编号+类型+值）           │
└──────────────┴──────────────────────────────────────────────────┘
```

帧头的 4 字节长度字段解决了 TCP 的粘包问题——TCP 是字节流，不知道消息边界在哪，接收方先读 4 字节拿到消息长度，再读对应长度的字节，就能精确提取出一条完整消息。`seqId` 是请求序列号，用于异步场景下匹配请求和响应（发出去的请求和收到的响应可能不是按顺序对应的）。

Mario 用的泛化调用绕过了 IDL 生成的 Stub，直接把 JSON 字符串交给 Thrift，Thrift 内部的 `json-simple` 序列化器负责把 JSON 转成上面这个二进制格式，服务端收到后再还原成 Java 对象。

Thrift 是美团在开源 Thrift 基础上的增强版，主要加了三件事：与 OCTO 服务注册中心集成实现服务发现、与 Mtrace 链路追踪集成、支持泳道路由让测试流量打到指定环境的机器。网络层用 Netty 实现，`setNettyIO(true)` 就是开启这个模式。

### Pigeon 原理

Pigeon 是大众点评自研的 RPC 框架，美团收购点评后两套框架并存，Mario 同时支持两者。

Pigeon 的整体架构和 Thrift 思路相同，差异主要在两点。第一是服务标识：Pigeon 用 HTTP 格式的 URL 标识服务（如 `http://service.dianping.com/xxx/yyy_1.0.0`），这是点评早期用 HTTP 通信留下的历史痕迹。第二是默认序列化方式：Pigeon 默认用 Hessian 而不是 Thrift 二进制，Hessian 是一种 Java 对象序列化格式，兼容性好但体积比 Thrift 二进制大。正因为 Hessian 在反序列化时需要明确知道目标类型，所以 Pigeon 的泛化调用必须显式传入 `paramTypes`。

---

## 四、与 Pigeon 的对比

| 维度 | Thrift（Thrift） | Pigeon | Pigeon |
|---|---|---|---|
| 出身 | Facebook 开源，美团增强 | 点评自研 | Google 开源 |
| 序列化 | Thrift 二进制 | Hessian（默认） | Protocol Buffers |
| 服务发现 | OCTO（开箱即用） | 点评注册中心（开箱即用） | 需要自己接入 OCTO/etcd/K8s |
| 链路追踪 | Mtrace（开箱即用） | CAT（开箱即用） | 需要自己接入 Jaeger/Zipkin |
| 流式通信 | 不支持 | 不支持 | 原生支持（基于 HTTP/2） |
| 多语言 | 20+ 种语言 | 主要 Java | 官方支持主流语言 |
| 云原生适配 | 弱 | 弱 | 强（天然适配 K8s/Istio） |

最关键的认知差异是：**Thrift 和 Pigeon 在美团/点评内部是"全家桶"**，服务发现、链路追踪、熔断限流全部配套开箱即用。Pigeon 本身只是一个通信框架，这些服务治理能力需要自己搭建。Pigeon 的优势在于 Protobuf 序列化性能最好、原生支持流式通信、云原生生态最完善，适合对外开放 API 或 Hulk 环境下的微服务场景。

序列化体积上，同样的数据：JSON 约 35 字节，Hessian 约 25 字节，Thrift 二进制约 15 字节，Protobuf 约 10 字节。Protobuf 最小的原因和 Thrift 一样——只传字段编号不传字段名，但 Protobuf 的编码方式更激进，用变长整数编码进一步压缩了数字类型的体积。

---

## 五、底层通信原理：TCP 字节流与帧协议

### 为什么需要"帧"

TCP 是一个纯粹的字节流协议，它只保证字节按顺序到达，完全不知道"一条消息"从哪里开始、到哪里结束。假设客户端连续发了两条消息 A（100字节）和 B（200字节），TCP 可能把它们合并成一个 300 字节的包一起发过来（粘包），也可能把消息 A 拆成两个包分两次发（拆包）。服务端如果直接读字节，根本不知道读到哪里算一条完整消息。

所有 RPC 框架解决这个问题的方式都一样：在应用层自己定义**帧格式**，在每条消息前面加一个固定长度的头，头里写明消息体有多少字节。接收方先读固定长度的头，从头里拿到消息体长度，再读对应字节数的消息体，就能精确还原出一条完整消息。

### Thrift 的帧格式（TFramedTransport）

Thrift 的帧格式是所有 RPC 框架里最简单的，就是 4 字节长度 + 消息体：

```
字节偏移:  0        1        2        3        4  ...  4+N
          ┌────────┬────────┬────────┬────────┬──────────────┐
          │                 N（消息体字节数，大端序 int32）     │  消息体（N字节）
          └────────┴────────┴────────┴────────┴──────────────┘
          ←────────── 帧头（4字节）──────────→←── 消息体 ──→
```

消息体内部是 TBinaryProtocol 编码的数据，结构如下：

```
消息体内部结构（TBinaryProtocol）：

字节 0-3：  版本号（0x80 0x01）+ 消息类型（0x00 0x01=CALL / 0x00 0x02=REPLY）
字节 4-7：  方法名字符串长度（int32）
字节 8-N：  方法名字符串（UTF-8）
字节 N+1 到 N+4：  seqId（序列号，int32，用于匹配请求和响应）
字节 N+5 起：  参数列表（每个字段：类型标识1字节 + 字段编号2字节 + 字段值）
最后1字节：  0x00（STOP 标志，表示字段列表结束）
```

字段编码示例，假设有一个 `orderId = 12345`（字段编号为 1，类型为 i64）：

```
0x0A          → 字段类型：TYPE_I64（10）
0x00 0x01     → 字段编号：1
0x00 0x00 0x00 0x00 0x00 0x00 0x30 0x39  → 值：12345（8字节大端序）
```

整条消息里没有出现 "orderId" 这个字符串，只有编号 1。这就是 Thrift 二进制体积小的根本原因。

### Pigeon 的帧格式

Pigeon 的帧格式比 Thrift 稍复杂，加了一个魔数用于校验：

```
字节偏移:  0    1    2    3    4    5    6    7    8  ...  8+N
          ┌────┬────┬────┬────┬────┬────┬────┬────┬──────────────┐
          │0xBA│0xBE│ 序列化类型 │    消息体字节数（int32）    │  消息体（N字节）
          └────┴────┴────┴────┴────┴────┴────┴────┴──────────────┘
          ←── 魔数 ──→←── 类型 ──→←──── 长度（4字节）────→
```

- `0xBA 0xBE`：魔数，接收方用来校验这是一个合法的 Pigeon 协议包，防止乱码数据被误处理
- 序列化类型（2字节）：标识消息体用的是哪种序列化方式（Hessian=1、Thrift=2、JSON=3）
- 消息体：Hessian 序列化后的 `PigeonRequest` 对象，包含 serviceUrl、methodName、parameterTypes、parameters、requestId 等字段

Pigeon 的 `requestId` 作用和 Thrift 的 `seqId` 完全一样，都是用来在异步场景下匹配请求和响应的。

### 请求-响应的完整时序

以客户端发一次 Thrift 调用为例，从发出请求到拿到响应，完整的时序如下：

```
业务线程                    Netty IO线程                    服务端
    │                           │                              │
    │  1. 序列化请求，写入Channel │                              │
    │──────────────────────────>│                              │
    │                           │  2. TCP发送字节流             │
    │                           │─────────────────────────────>│
    │  3. future.get() 阻塞等待  │                              │  4. Decoder提取完整帧
    │<══════════════════════════│                              │  5. 反序列化成Request对象
    │  （业务线程挂起，不占CPU）  │                              │  6. 业务逻辑执行
    │                           │                              │  7. 序列化Response
    │                           │  8. TCP返回响应字节流          │
    │                           │<─────────────────────────────│
    │                           │  9. Decoder提取完整帧         │
    │                           │  10. 根据seqId找到Future      │
    │  11. future.complete()    │                              │
    │<──────────────────────────│                              │
    │  业务线程被唤醒，拿到响应   │                              │
```

第 3 步业务线程调用 `future.get(timeout)` 后会挂起，让出 CPU，不会空转等待。第 10 步 IO 线程收到响应后，从一个 `ConcurrentHashMap<seqId, Future>` 里找到对应的 Future，调用 `future.complete(result)` 唤醒业务线程。这个"挂起-唤醒"机制是 Java 的 `LockSupport.park/unpark`，是 JVM 级别的线程调度，开销极小。

这套机制让一条 TCP 连接可以同时承载多个并发请求（每个请求有不同的 seqId），不需要为每个请求单独建一条连接，这就是 **TCP 连接复用**的本质。

---

## 六、手写 Netty 实现：Thrift 与 Pigeon 编解码器全套代码

下面从零手写两套完整的 Netty 实现，每一行代码都有注释解释原理。

### 6.1 Thrift 协议的 Netty 实现

#### 消息模型

```java
/**
 * Thrift 请求消息，对应一次 RPC 调用
 */
public class ThriftRequest {
    private int    seqId;        // 序列号，用于匹配响应
    private String methodName;   // 方法名
    private byte[] body;         // TBinaryProtocol 序列化后的参数字节
}

/**
 * Thrift 响应消息
 */
public class ThriftResponse {
    private int    seqId;        // 与请求的 seqId 对应
    private byte[] body;         // TBinaryProtocol 序列化后的返回值字节
    private String errorMessage; // 如果是异常响应，这里有错误信息
}
```

#### 帧解码器（处理粘包/拆包）

```java
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Thrift 帧解码器：从 TCP 字节流中提取出完整的 Thrift 帧
 *
 * 继承 ByteToMessageDecoder 是关键：
 * Netty 会把每次收到的字节追加到内部的 cumulation ByteBuf 里，
 * 然后反复调用 decode()，直到 decode() 不再往 out 里添加对象为止。
 * 这样我们完全不用自己处理"数据不够、等下一个包"的逻辑。
 */
public class ThriftFrameDecoder extends ByteToMessageDecoder {

    // Thrift 帧头固定 4 字节（存放消息体长度）
    private static final int FRAME_HEADER_LENGTH = 4;
    // 单帧最大允许 16MB，防止恶意大包撑爆内存
    private static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 可读字节不足 4 字节，连帧头都读不完，直接返回等下一个包
        if (in.readableBytes() < FRAME_HEADER_LENGTH) {
            return;
        }

        // 标记当前读指针位置，如果消息体还没到齐，可以 resetReaderIndex() 回退
        in.markReaderIndex();

        // 读取帧头：消息体的字节数（大端序 int32）
        int frameSize = in.readInt();

        // 合法性校验
        if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {
            // 非法帧，关闭连接，防止内存溢出
            ctx.close();
            throw new IllegalArgumentException("非法的 Thrift 帧大小: " + frameSize);
        }

        // 消息体还没有完整到达（网络分包），回退读指针，等下一次数据到来
        if (in.readableBytes() < frameSize) {
            in.resetReaderIndex(); // 把读指针退回到 markReaderIndex() 的位置
            return;                // 返回后 Netty 会继续等待数据
        }

        // 走到这里说明一个完整的帧已经到齐了，读出消息体
        ByteBuf frame = in.readBytes(frameSize);

        // 把完整的帧交给下一个 Handler（ThriftMessageDecoder）继续处理
        out.add(frame);
        // 注意：不要在这里 release frame，下一个 Handler 负责释放
    }
}
```

#### 消息解码器（把字节解析成 ThriftResponse 对象）

```java
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Thrift 消息解码器：把一个完整的帧（ByteBuf）解析成 ThriftResponse 对象
 *
 * 继承 MessageToMessageDecoder<ByteBuf>，
 * 上一个 Handler（ThriftFrameDecoder）传过来的是 ByteBuf，
 * 这里把它解析成业务对象 ThriftResponse。
 */
public class ThriftMessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    // TBinaryProtocol 的版本标识，高两字节固定是 0x8001
    private static final short THRIFT_VERSION_1 = (short) 0x8001;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        try {
            ThriftResponse response = new ThriftResponse();

            // ── 解析消息头 ──────────────────────────────────────────────
            // 读版本号+消息类型（4字节）
            // 高16位：版本号（0x8001 表示 TBinaryProtocol v1）
            // 低16位：消息类型（1=CALL, 2=REPLY, 3=EXCEPTION, 4=ONEWAY）
            int versionAndType = frame.readInt();
            short version  = (short) ((versionAndType >> 16) & 0xFFFF);
            short msgType  = (short) (versionAndType & 0xFFFF);

            if (version != THRIFT_VERSION_1) {
                throw new IllegalStateException("不支持的 Thrift 协议版本: " + version);
            }

            // 读方法名（4字节长度 + N字节字符串）
            int methodNameLen = frame.readInt();
            byte[] methodNameBytes = new byte[methodNameLen];
            frame.readBytes(methodNameBytes);
            // 方法名在响应里通常不用，但要把字节消费掉，保持读指针正确
            String methodName = new String(methodNameBytes, StandardCharsets.UTF_8);

            // 读 seqId（4字节），这是匹配请求的关键
            int seqId = frame.readInt();
            response.setSeqId(seqId);

            // ── 解析消息体 ──────────────────────────────────────────────
            // 剩余的字节就是 TBinaryProtocol 编码的返回值或异常
            // 这里直接把剩余字节存起来，交给业务层用 Thrift 库反序列化
            byte[] body = new byte[frame.readableBytes()];
            frame.readBytes(body);
            response.setBody(body);

            // 如果是异常响应（msgType == 3），解析错误信息
            if (msgType == 3) {
                response.setErrorMessage(parseExceptionMessage(body));
            }

            // 把解析好的响应对象传给下一个 Handler（ThriftClientHandler）
            out.add(response);

        } finally {
            // 释放 ByteBuf，防止内存泄漏
            // ByteToMessageDecoder 传过来的 frame 需要我们手动释放
            frame.release();
        }
    }

    private String parseExceptionMessage(byte[] body) {
        // 简化实现：Thrift 异常消息的第一个字段（编号1，类型STRING）就是 message
        // 实际生产代码会用 TBinaryProtocol 完整解析
        try {
            ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(body);
            buf.skipBytes(1 + 2); // 跳过字段类型(1) + 字段编号(2)
            int len = buf.readInt();
            byte[] msgBytes = new byte[len];
            buf.readBytes(msgBytes);
            return new String(msgBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "解析异常信息失败";
        }
    }
}
```

#### 消息编码器（把 ThriftRequest 对象序列化成字节帧）

```java
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Thrift 消息编码器：把 ThriftRequest 对象编码成带帧头的字节流
 *
 * 继承 MessageToByteEncoder<ThriftRequest>，
 * Netty 会在 channel.writeAndFlush(request) 时自动调用 encode()。
 */
public class ThriftMessageEncoder extends MessageToByteEncoder<ThriftRequest> {

    // TBinaryProtocol v1 的版本标识
    private static final int VERSION_1 = 0x80010000;
    // 消息类型：CALL（普通调用）
    private static final int MSG_TYPE_CALL = 1;

    @Override
    protected void encode(ChannelHandlerContext ctx, ThriftRequest request, ByteBuf out) {
        // 先把消息体写到一个临时 ByteBuf，因为帧头需要知道消息体的总长度
        ByteBuf body = ctx.alloc().buffer();
        try {
            // ── 写消息头（TBinaryProtocol 格式）──────────────────────────
            // 版本号 + 消息类型（4字节）
            body.writeInt(VERSION_1 | MSG_TYPE_CALL);

            // 方法名（4字节长度 + N字节字符串）
            byte[] methodNameBytes = request.getMethodName().getBytes(StandardCharsets.UTF_8);
            body.writeInt(methodNameBytes.length);
            body.writeBytes(methodNameBytes);

            // seqId（4字节）
            body.writeInt(request.getSeqId());

            // ── 写参数（已经是 TBinaryProtocol 序列化好的字节）──────────
            body.writeBytes(request.getBody());

            // ── 写帧头 + 消息体到输出 ByteBuf ────────────────────────────
            // 帧头：消息体总长度（4字节大端序）
            out.writeInt(body.readableBytes());
            // 消息体
            out.writeBytes(body);

        } finally {
            // 释放临时 ByteBuf
            body.release();
        }
    }
}
```

#### 客户端业务 Handler（管理 Future，实现请求-响应匹配）

```java
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thrift 客户端业务 Handler
 *
 * 职责：
 * 1. 维护 seqId → Future 的映射表，实现请求-响应匹配
 * 2. 收到响应时，根据 seqId 找到对应的 Future，唤醒等待的业务线程
 * 3. 提供 sendRequest() 方法供业务线程调用
 *
 * 注意：这个 Handler 是有状态的（pendingRequests），
 * 所以不能加 @ChannelHandler.Sharable，每个 Channel 要有独立的实例。
 */
public class ThriftClientHandler extends SimpleChannelInboundHandler<ThriftResponse> {

    // seqId 生成器，AtomicInteger 保证多线程安全，自增不重复
    private final AtomicInteger seqIdGenerator = new AtomicInteger(0);

    // 等待响应的请求表：seqId → Future
    // ConcurrentHashMap 保证多线程安全（业务线程写，IO线程读）
    private final ConcurrentHashMap<Integer, CompletableFuture<ThriftResponse>> pendingRequests
            = new ConcurrentHashMap<>();

    private ChannelHandlerContext ctx;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Channel 建立连接时保存 ctx，后续发请求用
        this.ctx = ctx;
    }

    /**
     * 发送请求并同步等待响应（业务线程调用）
     *
     * @param methodName 方法名
     * @param body       TBinaryProtocol 序列化好的参数字节
     * @param timeoutMs  超时毫秒
     * @return ThriftResponse
     */
    public ThriftResponse sendRequest(String methodName, byte[] body, long timeoutMs)
            throws Exception {

        // 1. 生成唯一 seqId
        int seqId = seqIdGenerator.incrementAndGet();

        // 2. 创建 Future，注册到等待表
        CompletableFuture<ThriftResponse> future = new CompletableFuture<>();
        pendingRequests.put(seqId, future);

        // 3. 构建请求对象
        ThriftRequest request = new ThriftRequest();
        request.setSeqId(seqId);
        request.setMethodName(methodName);
        request.setBody(body);

        // 4. 通过 Netty Channel 发送请求（非阻塞，立即返回）
        // writeAndFlush 会触发 Pipeline 中的 Encoder，把 ThriftRequest 编码成字节
        ctx.writeAndFlush(request).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                // 发送失败，从等待表移除，让 future.get() 抛出异常
                pendingRequests.remove(seqId);
                future.completeExceptionally(writeFuture.cause());
            }
        });

        // 5. 业务线程在这里阻塞等待，最多等 timeoutMs 毫秒
        // 底层是 LockSupport.parkNanos()，线程挂起不占 CPU
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 超时或异常，清理等待表，防止内存泄漏
            pendingRequests.remove(seqId);
            throw e;
        }
    }

    /**
     * 收到响应时由 Netty IO 线程调用（不是业务线程！）
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ThriftResponse response) {
        // 根据 seqId 找到对应的 Future
        CompletableFuture<ThriftResponse> future = pendingRequests.remove(response.getSeqId());

        if (future != null) {
            if (response.getErrorMessage() != null) {
                // 服务端返回了异常，让 future.get() 抛出异常
                future.completeExceptionally(new RuntimeException(response.getErrorMessage()));
            } else {
                // 正常响应，唤醒业务线程
                // future.complete() 会调用 LockSupport.unpark() 唤醒等待的业务线程
                future.complete(response);
            }
        }
        // 如果 future 为 null，说明请求已超时被清理，忽略这个响应
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 连接异常，让所有等待中的请求都失败
        pendingRequests.forEach((seqId, future) ->
                future.completeExceptionally(cause));
        pendingRequests.clear();
        ctx.close();
    }
}
```

#### 客户端启动器（组装 Pipeline）

```java
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Thrift Netty 客户端
 * 组装 Pipeline，建立连接，提供调用入口
 */
public class ThriftNettyClient {

    private final String host;
    private final int    port;

    private NioEventLoopGroup  workerGroup;
    private Channel            channel;
    private ThriftClientHandler clientHandler;

    public ThriftNettyClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws InterruptedException {
        // IO 线程池：专门处理网络读写，线程数默认 = CPU核数 * 2
        // 这些线程永远不能做阻塞操作（如 future.get()），否则会卡死整个网络层
        workerGroup = new NioEventLoopGroup();

        // 每个 Channel 独立的 Handler 实例（因为 ThriftClientHandler 有状态）
        clientHandler = new ThriftClientHandler();

        Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)  // 使用 NIO 非阻塞 Channel
                .option(ChannelOption.TCP_NODELAY, true)   // 禁用 Nagle 算法，减少延迟
                .option(ChannelOption.SO_KEEPALIVE, true)  // 开启 TCP 心跳，检测死连接
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // Pipeline 从左到右处理入站数据，从右到左处理出站数据：
                        //
                        // 入站（收到数据）：
                        //   网络字节 → ThriftFrameDecoder → ThriftMessageDecoder → ThriftClientHandler
                        //
                        // 出站（发送数据）：
                        //   ThriftClientHandler → ThriftMessageEncoder → 网络字节

                        // 入站 Handler 1：帧解码器，解决粘包/拆包
                        pipeline.addLast("frameDecoder", new ThriftFrameDecoder());

                        // 入站 Handler 2：消息解码器，把字节解析成 ThriftResponse 对象
                        pipeline.addLast("messageDecoder", new ThriftMessageDecoder());

                        // 出站 Handler：消息编码器，把 ThriftRequest 对象编码成字节帧
                        // 出站 Handler 加在哪里都行，它只处理出站数据
                        pipeline.addLast("messageEncoder", new ThriftMessageEncoder());

                        // 入站 Handler 3：业务 Handler，处理响应，唤醒业务线程
                        pipeline.addLast("clientHandler", clientHandler);
                    }
                });

        // 建立 TCP 连接，sync() 等待连接完成
        ChannelFuture connectFuture = bootstrap.connect(host, port).sync();
        channel = connectFuture.channel();
        System.out.println("已连接到 Thrift 服务端: " + host + ":" + port);
    }

    /**
     * 发起一次 RPC 调用
     *
     * @param methodName 方法名
     * @param body       TBinaryProtocol 序列化好的参数
     * @param timeoutMs  超时毫秒
     */
    public ThriftResponse invoke(String methodName, byte[] body, long timeoutMs) throws Exception {
        return clientHandler.sendRequest(methodName, body, timeoutMs);
    }

    public void shutdown() {
        if (channel != null) channel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }
}
```

---

### 6.2 Pigeon 协议的 Netty 实现

Pigeon 的实现结构和 Thrift 完全一样，差异只在帧格式和序列化方式。

#### 消息模型

```java
/**
 * Pigeon 请求消息
 */
public class PigeonRequest {
    private long     requestId;      // 请求 ID（long，比 Thrift 的 int seqId 范围更大）
    private String   serviceUrl;     // 服务 URL，如 http://service.dianping.com/xxx
    private String   methodName;     // 方法名
    private String[] parameterTypes; // 参数类型列表，如 ["java.lang.Long", "java.lang.String"]
    private Object[] parameters;     // 参数值列表
    private int      serialize;      // 序列化类型：1=Hessian, 2=Thrift, 3=JSON
}

/**
 * Pigeon 响应消息
 */
public class PigeonResponse {
    private long   requestId;    // 与请求的 requestId 对应
    private Object result;       // 反序列化后的返回值
    private String errorMessage; // 异常信息
    private int    status;       // 0=成功, 非0=失败
}
```

#### 帧解码器

```java
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Pigeon 帧解码器
 *
 * Pigeon 帧格式：
 *   魔数(2字节) + 序列化类型(2字节) + 消息体长度(4字节) + 消息体(N字节)
 * 帧头共 8 字节。
 */
public class PigeonFrameDecoder extends ByteToMessageDecoder {

    // Pigeon 协议魔数，用于校验包的合法性
    private static final short Pigeon_MAGIC = (short) 0xBABE;
    // 帧头长度：魔数(2) + 序列化类型(2) + 消息体长度(4) = 8字节
    private static final int FRAME_HEADER_LENGTH = 8;
    private static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 帧头都没到齐，等待
        if (in.readableBytes() < FRAME_HEADER_LENGTH) {
            return;
        }

        in.markReaderIndex();

        // 读魔数（2字节），校验是否是合法的 Pigeon 包
        short magic = in.readShort();
        if (magic != Pigeon_MAGIC) {
            ctx.close();
            throw new IllegalStateException("非法的 Pigeon 魔数: 0x" + Integer.toHexString(magic & 0xFFFF));
        }

        // 读序列化类型（2字节）：1=Hessian, 2=Thrift, 3=JSON
        short serializeType = in.readShort();

        // 读消息体长度（4字节）
        int frameSize = in.readInt();
        if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {
            ctx.close();
            throw new IllegalArgumentException("非法的 Pigeon 帧大小: " + frameSize);
        }

        // 消息体还没到齐，回退等待
        if (in.readableBytes() < frameSize) {
            in.resetReaderIndex();
            return;
        }

        // 读出完整消息体
        ByteBuf frame = in.readBytes(frameSize);

        // 把序列化类型和消息体一起传给下一个 Handler
        // 用一个简单的包装对象携带这两个信息
        out.add(new PigeonRawFrame(serializeType, frame));
    }

    /** 携带序列化类型和原始字节的中间对象 */
    public static class PigeonRawFrame {
        public final short   serializeType;
        public final ByteBuf data;
        public PigeonRawFrame(short serializeType, ByteBuf data) {
            this.serializeType = serializeType;
            this.data = data;
        }
    }
}
```

#### 消息解码器（Hessian 反序列化）

```java
import com.caucho.hessian.io.HessianInput;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * Pigeon 消息解码器：把原始字节用 Hessian 反序列化成 PigeonResponse 对象
 */
public class PigeonMessageDecoder extends MessageToMessageDecoder<PigeonFrameDecoder.PigeonRawFrame> {

    @Override
    protected void decode(ChannelHandlerContext ctx,
                          PigeonFrameDecoder.PigeonRawFrame rawFrame,
                          List<Object> out) throws Exception {
        try {
            byte[] bytes = new byte[rawFrame.data.readableBytes()];
            rawFrame.data.readBytes(bytes);

            PigeonResponse response;

            if (rawFrame.serializeType == 1) {
                // Hessian 反序列化
                response = deserializeWithHessian(bytes);
            } else if (rawFrame.serializeType == 3) {
                // JSON 反序列化（简化实现）
                response = deserializeWithJson(bytes);
            } else {
                throw new UnsupportedOperationException("不支持的序列化类型: " + rawFrame.serializeType);
            }

            out.add(response);
        } finally {
            rawFrame.data.release();
        }
    }

    private PigeonResponse deserializeWithHessian(byte[] bytes) throws Exception {
        // Hessian 反序列化：直接把字节流还原成 Java 对象
        // Hessian 的特点是不需要 schema，直接序列化 Java 对象图
        // 但反序列化时必须知道目标类型，这就是 Pigeon 需要 paramTypes 的原因
        HessianInput input = new HessianInput(new ByteArrayInputStream(bytes));
        return (PigeonResponse) input.readObject();
    }

    private PigeonResponse deserializeWithJson(byte[] bytes) throws Exception {
        // JSON 反序列化（实际项目用 fastjson 或 jackson）
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        // 简化：实际需要解析 json 字段
        PigeonResponse response = new PigeonResponse();
        // ... 解析逻辑
        return response;
    }
}
```

#### 消息编码器（Hessian 序列化）

```java
import com.caucho.hessian.io.HessianOutput;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.io.ByteArrayOutputStream;

/**
 * Pigeon 消息编码器：把 PigeonRequest 用 Hessian 序列化，加上帧头写出去
 */
public class PigeonMessageEncoder extends MessageToByteEncoder<PigeonRequest> {

    private static final short Pigeon_MAGIC    = (short) 0xBABE;
    private static final short SERIALIZE_HESSIAN = 1;

    @Override
    protected void encode(ChannelHandlerContext ctx, PigeonRequest request, ByteBuf out)
            throws Exception {

        // 1. 用 Hessian 序列化请求对象
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HessianOutput hessianOutput = new HessianOutput(baos);
        hessianOutput.writeObject(request); // 把整个 PigeonRequest 对象序列化
        hessianOutput.flush();
        byte[] body = baos.toByteArray();

        // 2. 写帧头
        out.writeShort(Pigeon_MAGIC);         // 魔数（2字节）
        out.writeShort(SERIALIZE_HESSIAN);    // 序列化类型（2字节）
        out.writeInt(body.length);            // 消息体长度（4字节）

        // 3. 写消息体
        out.writeBytes(body);
    }
}
```

#### 客户端业务 Handler

```java
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pigeon 客户端业务 Handler
 *
 * 和 ThriftClientHandler 逻辑完全一样，
 * 差异只是 requestId 用 long（Pigeon 用 long，Thrift 用 int）。
 */
public class PigeonClientHandler extends SimpleChannelInboundHandler<PigeonResponse> {

    // Pigeon 用 long 类型的 requestId，范围比 Thrift 的 int seqId 大得多
    private final AtomicLong requestIdGenerator = new AtomicLong(0);

    private final ConcurrentHashMap<Long, CompletableFuture<PigeonResponse>> pendingRequests
            = new ConcurrentHashMap<>();

    private ChannelHandlerContext ctx;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    public PigeonResponse sendRequest(PigeonRequest request, long timeoutMs) throws Exception {
        // 生成唯一 requestId
        long requestId = requestIdGenerator.incrementAndGet();
        request.setRequestId(requestId);

        CompletableFuture<PigeonResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        // 发送请求（非阻塞）
        ctx.writeAndFlush(request).addListener(f -> {
            if (!f.isSuccess()) {
                pendingRequests.remove(requestId);
                future.completeExceptionally(f.cause());
            }
        });

        // 业务线程阻塞等待
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw e;
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, PigeonResponse response) {
        // IO 线程收到响应，根据 requestId 唤醒对应的业务线程
        CompletableFuture<PigeonResponse> future = pendingRequests.remove(response.getRequestId());
        if (future != null) {
            if (response.getStatus() != 0) {
                future.completeExceptionally(new RuntimeException(response.getErrorMessage()));
            } else {
                future.complete(response);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        pendingRequests.forEach((id, future) -> future.completeExceptionally(cause));
        pendingRequests.clear();
        ctx.close();
    }
}
```

#### 客户端启动器

```java
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Pigeon Netty 客户端
 */
public class PigeonNettyClient {

    private final String host;
    private final int    port;

    private NioEventLoopGroup  workerGroup;
    private Channel            channel;
    private PigeonClientHandler clientHandler;

    public PigeonNettyClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws InterruptedException {
        workerGroup   = new NioEventLoopGroup();
        clientHandler = new PigeonClientHandler();

        Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // 入站链路（收到数据时从上往下执行）：
                        //   网络字节
                        //     → PigeonFrameDecoder（提取完整帧，处理粘包）
                        //     → PigeonMessageDecoder（Hessian 反序列化成 PigeonResponse）
                        //     → PigeonClientHandler（根据 requestId 唤醒业务线程）
                        //
                        // 出站链路（发送数据时从下往上执行）：
                        //   PigeonClientHandler（调用 writeAndFlush）
                        //     → PigeonMessageEncoder（Hessian 序列化 + 加帧头）
                        //     → 网络字节

                        pipeline.addLast("frameDecoder",   new PigeonFrameDecoder());
                        pipeline.addLast("messageDecoder", new PigeonMessageDecoder());
                        pipeline.addLast("messageEncoder", new PigeonMessageEncoder());
                        pipeline.addLast("clientHandler",  clientHandler);
                    }
                });

        channel = bootstrap.connect(host, port).sync().channel();
        System.out.println("已连接到 Pigeon 服务端: " + host + ":" + port);
    }

    public PigeonResponse invoke(String serviceUrl, String methodName,
                                  String[] paramTypes, Object[] params,
                                  long timeoutMs) throws Exception {
        PigeonRequest request = new PigeonRequest();
        request.setServiceUrl(serviceUrl);
        request.setMethodName(methodName);
        request.setParameterTypes(paramTypes);
        request.setParameters(params);
        request.setSerialize(1); // Hessian
        return clientHandler.sendRequest(request, timeoutMs);
    }

    public void shutdown() {
        if (channel != null) channel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }
}
```

---

### 6.3 两套实现的对比总结

把两套实现放在一起看，结构完全对称，差异只在协议细节：

| 组件 | Thrift 实现 | Pigeon 实现 | 差异说明 |
|---|---|---|---|
| 帧解码器 | `ThriftFrameDecoder` | `PigeonFrameDecoder` | Pigeon 多了魔数校验和序列化类型字段 |
| 消息解码器 | `ThriftMessageDecoder` | `PigeonMessageDecoder` | Thrift 解析二进制协议头；Pigeon 用 Hessian 反序列化 |
| 消息编码器 | `ThriftMessageEncoder` | `PigeonMessageEncoder` | Thrift 手写二进制编码；Pigeon 用 Hessian 序列化整个对象 |
| 业务 Handler | `ThriftClientHandler` | `PigeonClientHandler` | 逻辑完全一样，seqId 类型不同（int vs long） |
| 请求 ID | `seqId`（int，4字节） | `requestId`（long，8字节） | Pigeon 支持更大的并发请求数 |
| 序列化 | TBinaryProtocol（手写字段编号） | Hessian（直接序列化 Java 对象） | Thrift 更紧凑；Hessian 更简单但体积大 |
| 粘包解决 | 4字节帧长度 | 2字节魔数 + 2字节类型 + 4字节帧长度 | 本质相同，都是"长度前缀"方案 |

`ByteToMessageDecoder`、`CompletableFuture`、`ConcurrentHashMap<seqId, Future>` 这三个是所有 RPC 框架网络层的通用骨架，Dubbo 的 `NettyCodecAdapter` + `DefaultFuture`、Thrift 的内部实现、Pigeon 的 `dpsf-net`，本质上都是这套模型，只是协议格式和序列化方式不同。
