核心组件：
NameNode（NN）：管理元数据（Block 位置、文件目录），对应 NameNode.java/NameNodeRpcServer.java；
DataNode（DN）：存储实际 Block 数据，对应 DataNode.java/DataXceiver.java；
Client：通过 DFSClient.java 与 NN/DN 交互；
HA 相关：ZKFC（ZooKeeper Failover Controller）处理脑裂 / 主备切换，对应 ZKFailoverController.java。
一、HDFS 读流程（核心源码 + 执行逻辑）
HDFS 读流程核心是「Client 先从 NN 获取 Block 位置，再直连 DN 读数据」，全程 NN 不参与数据传输。
1. 读流程核心步骤

步骤	执行方	核心操作
1	Client	调用 open() 打开文件，向 NN 请求目标文件的 Block 位置；
2	NN	校验权限 / 文件存在性，返回 Block 列表 + 对应 DN 地址；
3	Client	选择最优 DN（同机架 / 低负载），建立 TCP 连接；
4	DN	校验 Block 完整性（校验和），返回数据；
5	Client	拼接 Block 数据，返回给应用。


2. 核心源码片段 & 解释
（1）Client 侧：发起读请求，获取 Block 位置
源码路径：org/apache/hadoop/hdfs/DFSClient.java
java
运行
// 核心方法：打开文件输入流
public DFSInputStream open(String src, int buffersize) throws IOException {
  checkOpen();
  // 1. 构造输入流，初始化 Block 信息
  DFSInputStream dfsis = new DFSInputStream(this, src, buffersize);
  // 2. 从 NN 获取 Block 位置（核心：openInfo）
  dfsis.openInfo(true); 
  return dfsis;
}

// DFSInputStream 内部方法：向 NN 查询 Block 位置
void openInfo(boolean refresh) throws IOException {
  synchronized (infoLock) {
    // 调用 NN 的 RPC 接口：getBlockLocations
    LocatedBlocks locatedBlocks = callGetBlockLocations(namenode, src, 0, Long.MAX_VALUE);
    if (locatedBlocks == null || locatedBlocks.getLocatedBlocks().isEmpty()) {
      throw new FileNotFoundException("File " + src + " does not exist.");
    }
    // 缓存 Block 位置信息，供后续选 DN 使用
    this.locatedBlocks = locatedBlocks;
    this.fileLength = locatedBlocks.getFileLength();
  }
}

// 调用 NN RPC 的核心方法
private LocatedBlocks callGetBlockLocations(NameNodeProtocol namenode, String src, long offset, long length) throws IOException {
  return namenode.getBlockLocations(src, offset, length);
}
关键逻辑：
openInfo(true) 是核心：触发 Client 向 NN 发起 getBlockLocations RPC 请求；
LocatedBlocks 包含所有 Block 的位置（DN 列表）、文件长度等元数据，Client 缓存后无需重复请求 NN。
（2）NN 侧：处理 Block 位置查询
源码路径：org/apache/hadoop/hdfs/server/namenode/NameNodeRpcServer.java
java
运行
// NN 暴露的 RPC 接口：返回文件的 Block 位置
@Override
public LocatedBlocks getBlockLocations(String src, long offset, long length) throws IOException {
  // 权限校验（核心前置）
  checkNNStartup();
  authorizationManager.checkFsPermission(getRemoteUser(), src, FsAction.READ);
  
  // 核心：从 FSNamesystem 获取 Block 位置
  return namesystem.getBlockLocations(src, offset, length, true);
}

// FSNamesystem 核心方法：查询元数据并返回
public LocatedBlocks getBlockLocations(String src, long offset, long length, boolean needBlockToken) throws IOException {
  // 1. 解析文件路径，获取 INode（文件节点）
  INodeFile file = getINodeFile(src, true);
  if (file == null) throw new FileNotFoundException(src);
  
  // 2. 计算 offset/length 覆盖的 Block 范围
  long fileLen = file.computeFileSize();
  long start = Math.max(0, offset);
  long end = Math.min(fileLen, offset + length);
  
  // 3. 获取 Block 列表 + 对应 DN 位置
  List<LocatedBlock> blocks = file.getBlockLocations(start, end - start);
  
  // 4. 封装返回（过滤死亡 DN、补充 Token）
  return new LocatedBlocks(fileLen, blocks, needBlockToken);
}
关键逻辑：
NN 仅查询内存中的元数据（INodeFile 是文件的内存表示），不涉及磁盘 IO，保证高性能；
自动过滤「心跳超时的 DN」（死亡节点），只返回健康 DN 列表。
（3）DN 侧：处理读 Block 请求
源码路径：org/apache/hadoop/hdfs/server/datanode/DataXceiver.java
java
运行
// DN 处理读 Block 请求的核心方法
private void readBlock(PacketHeader header, DataInputStream in) throws IOException {
  // 1. 解析请求头：Block ID、偏移量、读取长度
  long blockId = header.getBlockId();
  long startOffset = header.getReadOffset();
  long len = header.getReadLength();
  
  // 2. 校验 Block 存在性 + 完整性（校验和）
  Block block = new Block(blockId);
  BlockMetadataInfo meta = dataNode.getBlockManager().getStoredBlock(block).getMetadata();
  // 校验和验证：防止数据损坏
  if (!meta.verifyChecksum(startOffset, len)) {
    throw new IOException("Block " + blockId + " checksum failed.");
  }
  
  // 3. 读取本地 Block 数据并返回给 Client
  try (FileInputStream fis = new FileInputStream(getBlockFile(block))) {
    fis.skip(startOffset);
    byte[] buf = new byte[4096];
    int read;
    while ((read = fis.read(buf)) != -1 && len > 0) {
      len -= read;
      out.write(buf, 0, read); // 直接写回 Client 流
    }
  }
}
关键逻辑：
DN 先校验 Block 校验和（verifyChecksum），损坏则直接抛异常，Client 会自动切换 DN 重试；
数据直接通过 TCP 流返回 Client，无 NN 中转。



二、HDFS 写流程（核心源码 + 执行逻辑）
HDFS 写流程核心是「Client 先向 NN 申请 Block，再建立 DN 流水线（Pipeline）写入，最后确认」，保证副本一致性。
1. 写流程核心步骤

步骤	执行方	核心操作
1	Client	调用 create() 创建文件，向 NN 申请 Block；
2	NN	分配 Block ID + 副本 DN 列表（Pipeline）；
3	Client	建立 Pipeline，将数据拆分为 Packet 写入第一个 DN；
4	DN	逐节点转发 Packet，最后一个 DN 向 Client 发确认；
5	Client	写完 Block，向 NN 汇报，关闭 Pipeline。



2. 核心源码片段 & 解释
（1）Client 侧：申请 Block + 建立 Pipeline
源码路径：org/apache/hadoop/hdfs/DFSClient.java
java
运行
// 核心方法：创建文件输出流
public DFSOutputStream create(String src, FsPermission permission, boolean overwrite,
                              boolean createParent, short replication, long blockSize) throws IOException {
  checkOpen();
  // 1. 向 NN 发起创建文件 RPC 请求
  namenode.create(src, permission, getRemoteUser(), overwrite, createParent, replication, blockSize);
  
  // 2. 构造输出流，初始化 Pipeline
  DFSOutputStream out = new DFSOutputStream(this, src, blockSize, replication);
  // 3. 申请第一个 Block（核心：addBlock）
  out.nextBlockOutputStream();
  return out;
}

// DFSOutputStream 内部方法：申请新 Block
void nextBlockOutputStream() throws IOException {
  // 调用 NN RPC：addBlock 申请 Block + DN 列表
  LocatedBlock lb = namenode.addBlock(src, clientName, null, null);
  // 封装 Block 信息
  currentBlock = lb.getBlock();
  // 核心：建立 DN 流水线（Pipeline）
  setPipeline(lb.getLocations());
  // 启动数据转发线程（DataStreamer）
  dataStreamer.start();
}

// 建立 Pipeline：按 NN 返回的 DN 列表构建 TCP 连接
void setPipeline(DatanodeInfo[] targets) throws IOException {
  this.pipeline = new LinkedList<>();
  for (DatanodeInfo dn : targets) {
    // 建立与 DN 的 TCP 连接
    Socket sock = new Socket(dn.getIpAddr(), dn.getXferPort());
    pipeline.add(sock);
  }
}
关键逻辑：
addBlock 是核心 RPC：NN 分配唯一 Block ID + 3 个 DN（默认副本数）；
DataStreamer 是后台线程，负责将 Packet 写入 Pipeline 第一个 DN。
（2）NN 侧：分配 Block + 副本 DN
源码路径：org/apache/hadoop/hdfs/server/namenode/NameNodeRpcServer.java
java
运行
// NN 暴露的 RPC 接口：分配 Block
@Override
public LocatedBlock addBlock(String src, String clientName, 
                            DatanodeInfo[] excludedNodes, String[] favoredNodes) throws IOException {
  checkNNStartup();
  authorizationManager.checkFsPermission(getRemoteUser(), src, FsAction.WRITE);
  
  // 核心：FSNamesystem 分配 Block
  return namesystem.addBlock(src, clientName, excludedNodes, favoredNodes);
}

// FSNamesystem 核心方法：分配 Block + 选 DN
public LocatedBlock addBlock(String src, String clientName, 
                            DatanodeInfo[] excludedNodes, String[] favoredNodes) throws IOException {
  // 1. 获取文件 INode
  INodeFile file = getINodeFile(src, true);
  
  // 2. 选 DN 列表（副本策略：跨机架、低负载）
  DatanodeInfo[] targets = blockPlacementPolicy.chooseTarget(
    file.getReplication(), excludedNodes, favoredNodes, src, file);
  
  // 3. 分配唯一 Block ID
  Block block = new Block(blockManager.getNextBlockId());
  
  // 4. 记录 Block 元数据（文件-Block 映射）
  file.addBlock(block, targets);
  
  // 5. 封装返回
  return new LocatedBlock(block, targets);
}
关键逻辑：
blockPlacementPolicy 是副本放置策略（默认 BlockPlacementPolicyDefault），保证 DN 跨机架分布；
getNextBlockId() 生成全局唯一 Block ID，避免冲突。
（3）DN 侧：处理写 Block + 转发 Pipeline
源码路径：org/apache/hadoop/hdfs/server/datanode/DataXceiver.java
java
运行
// DN 处理写 Block 请求的核心方法
private void writeBlock(PacketHeader header, DataInputStream in) throws IOException {
  // 1. 解析请求：Block ID、Pipeline 列表
  long blockId = header.getBlockId();
  DatanodeInfo[] pipeline = header.getPipeline();
  
  // 2. 本地写入前准备：创建 Block 文件 + WAL
  Block block = new Block(blockId);
  File blockFile = getBlockFile(block);
  File walFile = new File(blockFile.getParent(), blockId + ".wal");
  // 写前日志（WAL）：保证数据不丢失
  FileOutputStream walOut = new FileOutputStream(walFile, true);
  
  // 3. 读取 Packet 并写入 WAL + 本地 Block 文件
  byte[] packet = new byte[header.getPacketLength()];
  in.readFully(packet);
  walOut.write(packet); // 先写 WAL
  try (FileOutputStream blockOut = new FileOutputStream(blockFile, true)) {
    blockOut.write(packet); // 再写 Block 数据
  }
  
  // 4. 转发 Packet 到 Pipeline 下一个 DN（最后一个 DN 无需转发）
  if (pipeline.length > 1) {
    DatanodeInfo nextDN = pipeline[1];
    Socket nextSock = new Socket(nextDN.getIpAddr(), nextDN.getXferPort());
    OutputStream nextOut = nextSock.getOutputStream();
    nextOut.write(packet); // 转发 Packet
    nextOut.flush();
  }
  
  // 5. 最后一个 DN 向 Client 发送确认
  if (pipeline.length == 1) {
    out.write(new ACKHeader(blockId).toBytes());
  }
}
关键逻辑：
先写 WAL（写前日志）再写 Block 数据，防止 DN 宕机导致数据丢失；
Pipeline 是链式转发：第一个 DN 写本地 + 转发给第二个，第二个写本地 + 转发给第三个，第三个写本地 + 发确认。












三、常见异常处理（脑裂、宕机等）




1. NameNode 脑裂（Split Brain）
场景：HA 模式下，主 NN（Active）和备 NN（Standby）同时认为自己是 Active，导致元数据双写冲突。核心处理机制：ZKFC + 租约锁（ZooKeeper 独占锁），保证同一时间只有一个 Active NN。
核心源码（ZKFC）
源码路径：org/apache/hadoop/hdfs/server/namenode/ha/ZKFailoverController.java
java
运行
// ZKFC 核心方法：选举 Active NN
private void electActive() throws ZooKeeperConnectionException, InterruptedException {
  // 1. 尝试获取 ZK 独占锁（/hadoop-ha/{clusterId}/ActiveStandbyElectorLock）
  try (ZKCheckedEphemeralNode lock = new ZKCheckedEphemeralNode(zkClient, lockPath)) {
    // 2. 获取锁成功：成为 Active
    if (lock.create()) {
      LOG.info("Successfully acquired Active lock.");
      // 触发 NN 切换为 Active 状态
      transitionToActive();
      return;
    }
    // 3. 获取锁失败：成为 Standby
    LOG.info("Failed to acquire Active lock, becoming Standby.");
    transitionToStandby();
  }
}

// 切换 NN 状态为 Active
private void transitionToActive() throws IOException {
  // 校验：强制关闭其他可能的 Active NN（脑裂兜底）
  haAdmin.transitionToActive(conf, true); // true = 强制关闭冲突 NN
  // 更新 NN 状态为 Active
  namenode.getServiceState().setState(HAState.ACTIVE);
}
关键逻辑：
ZK 的「临时节点锁」是核心：只有一个 ZKFC 能创建 /hadoop-ha/{clusterId}/ActiveStandbyElectorLock 临时节点；
若检测到双 Active，ZKFC 会调用 haAdmin.transitionToActive(true) 强制关闭冲突 NN，杜绝脑裂。










脑裂的原因

NameNode 脑裂的本质是ZKFC 依赖的 ZooKeeper 独占租约锁机制失效——ZK 的临时节点锁本应保证 “同一时间只有一个 NN 持有 Active 身份”，但当锁的 “唯一性” 被打破时，多个 NN 会同时认为自己持有锁，进而成为 Active。以下是具体触发场景及底层逻辑：
一、核心触发场景（按发生概率排序）
1. 网络分区（最常见）
这是引发脑裂的首要原因，本质是 “网络隔离导致锁的状态感知不一致”。
场景描述：集群网络被分割为两个 / 多个孤立的分区（比如交换机故障、跨机房网络中断）：
Active NN 所在节点与 ZooKeeper 集群网络断连，但 Active NN 本身进程正常，且能和所在分区内的 DataNode 通信；
Standby NN 所在节点与 ZooKeeper 集群网络正常，ZK 检测到 Active NN 的临时锁节点因 “会话超时” 被自动删除（ZK 临时节点依赖心跳维持，断连后会话超时则节点消失）；
Standby NN 的 ZKFC 立即抢占锁节点，调用transitionToActive()切换为 Active。
结果：两个分区内的 NN 都认为自己是 Active—— 原 Active NN 因网络隔离无法感知锁已丢失，新 Active NN 则合法抢占了锁，最终导致 DataNode 可能向两个 NN 汇报数据、元数据双写冲突。
2. ZKFC 进程异常 / 挂死
ZKFC 是 “锁的维护者”，负责向 ZK 发送心跳维持租约，若它异常则锁的状态会失控：
场景描述：Active NN 节点上的 ZKFC 进程崩溃、卡死或被误杀，但 Active NN 本身进程正常；
底层逻辑：
ZKFC 挂死后，无法向 ZK 发送心跳，ZK 会在zookeeper.session.timeout（默认 5-10 秒）后删除 Active NN 的临时锁节点；
Standby NN 的 ZKFC 检测到锁节点消失，立即抢占锁并切换为 Active；
原 Active NN 因 ZKFC 挂死，无法感知 “锁已丢失”，仍持续以 Active 状态运行。
3. ZooKeeper 集群自身脑裂
ZK 集群是 “锁的仲裁者”，若它自身出现脑裂，仲裁逻辑会失效：
场景描述：ZK 集群因节点故障 / 网络隔离，分裂为多个 “小集群”，且每个小集群都认为自己是 “法定集群”（满足 ZK 的 Quorum 过半机制）；
底层逻辑：不同的 ZK 小集群可能分别向不同的 NN 发放 “独占锁”—— 比如 ZK 集群 A 给原 Active NN 续期锁，ZK 集群 B 给 Standby NN 发放新锁，最终两个 NN 都认为自己持有合法锁，成为 Active。
4. 租约超时配置不合理
配置参数的不当设置会导致锁的 “误释放”：
场景描述：
ZK 会话超时时间（zookeeper.session.timeout）配置过短（比如小于 3 秒）；
Active NN 节点负载过高（CPU / 内存占满），导致 ZKFC 无法及时发送心跳维持会话；
底层逻辑：Active NN 的 ZKFC 因节点卡顿，未能在超时时间内给 ZK 发心跳，ZK 判定会话失效并删除锁节点；Standby NN 抢占锁成为 Active，而原 Active NN 负载恢复后仍未感知锁丢失，继续以 Active 运行。
5. 手动操作不当（人为失误）
运维操作绕过 ZKFC 的锁机制，直接打破唯一性：
场景描述：
运维人员绕过 ZKFC，直接执行hdfs haadmin -transitionToActive --forcemanual <nn-id>强制将 Standby 设为 Active，且未关闭原 Active NN；
同时在两个 NN 节点执行状态切换命令；
底层逻辑：手动操作跳过了 ZK 锁的校验流程，直接修改 NN 状态，导致双 Active。
6. NN 与 ZKFC 进程通信异常
同一节点内 NN 和 ZKFC 的 IPC 通信断连，导致状态感知失效：
场景描述：Active NN 节点上，NN 进程正常，但 ZKFC 与 NN 之间的内部通信（IPC）被防火墙 / 进程阻塞；
底层逻辑：ZKFC 无法向 NN 同步锁的状态，NN 也无法通过 ZKFC 感知 “锁是否丢失”，持续以 Active 运行；而 Standby NN 的 ZKFC 抢占锁后成为 Active，最终引发脑裂。










































2. NameNode 宕机（Active NN 故障）
场景：Active NN 宕机，需快速切换到 Standby NN，保证服务不中断。
核心源码（ZKFC 故障转移）
源码路径：org/apache/hadoop/hdfs/server/namenode/ha/ZKFailoverController.java
java
运行
// ZKFC 核心方法：故障转移
public void failover() throws IOException {
  // 1. 检测旧 Active NN 状态
  boolean oldActiveDead = !haAdmin.isActive(conf, oldActiveAddr);
  
  // 2. 若旧 Active 已死：直接选举新 Active
  if (oldActiveDead) {
    electActive();
    return;
  }
  
  // 3. 若旧 Active 存活：强制关闭（避免脑裂）
  LOG.warn("Old Active NN is still alive, forcing shutdown.");
  haAdmin.killActive(conf, oldActiveAddr);
  
  // 4. 选举新 Active
  electActive();
  
  // 5. 新 Active 同步 EditLog（从 QJM）
  standbyNN.getEditLogTailer().catchup();
}
关键逻辑：
QJM（Quorum Journal Manager）是元数据同步核心：Active NN 的所有元数据变更（EditLog）都会写入 QJM 集群；
Standby NN 实时拉取 QJM 的 EditLog，宕机切换后只需加载最新 EditLog，即可恢复元数据。




3. DataNode 宕机
场景：DN 心跳超时（默认 10 分钟），NN 标记其为 Dead，触发副本重建。
核心源码（ReplicationMonitor）
源码路径：org/apache/hadoop/hdfs/server/namenode/ReplicationMonitor.java
java
运行
// NN 后台线程：监控副本数量，处理 DN 宕机
@Override
public void run() {
  while (namesystem.isRunning()) {
    try {
      // 1. 扫描所有 Block，计算副本不足的 Block
      computeDatanodeWork();
      
      // 2. 处理副本不足（DN 宕机导致）
      processUnderReplicatedBlocks();
      
      // 3. 休眠（默认 3 秒）
      Thread.sleep(3000);
    } catch (InterruptedException e) {
      LOG.warn("ReplicationMonitor interrupted.", e);
    }
  }
}

// 核心方法：处理副本不足的 Block
private void processUnderReplicatedBlocks() {
  // 1. 获取所有副本数 < 配置值的 Block
  Collection<Block> underReplicated = blockManager.getUnderReplicatedBlocks();
  
  for (Block block : underReplicated) {
    // 2. 选新 DN 重建副本
    DatanodeInfo[] newTargets = blockPlacementPolicy.chooseTarget(
      replication, excludedNodes, null, src, file);
    
    // 3. 向源 DN 发送副本复制指令
    blockManager.addPendingReplication(block, newTargets);
  }
}
关键逻辑：
ReplicationMonitor 是 NN 后台线程，每 3 秒扫描一次副本状态；
若 DN 宕机导致 Block 副本数 < 3，NN 会选新 DN，指令源 DN（健康 DN）将 Block 复制到新 DN，补全副本。




4. Pipeline 断裂（部分 DN 写入失败）
场景：写流程中某个 DN 写入失败，Pipeline 断裂，Client 需重试并重建 Pipeline。
核心源码（DFSOutputStream 异常处理）
源码路径：org/apache/hadoop/hdfs/DFSOutputStream.java
java
运行
// 处理 Pipeline 异常的核心方法
private void handlePipelineException(IOException e) throws IOException {
  // 1. 停止当前 DataStreamer 线程
  dataStreamer.interrupt();
  
  // 2. 标记失败 DN，从 Pipeline 移除
  DatanodeInfo failedDN = getFailedDN(e);
  pipeline.remove(failedDN);
  
  // 3. 向 NN 申请新 DN 补充 Pipeline
  LocatedBlock newLB = namenode.addBlock(src, clientName, new DatanodeInfo[]{failedDN}, null);
  pipeline.add(newLB.getLocations()[0]);
  
  // 4. 重建 Pipeline，重试写入
  setPipeline(pipeline.toArray(new DatanodeInfo[0]));
  dataStreamer.restart();
  
  // 5. 若重试 3 次仍失败，抛异常
  if (retryCount >= 3) {
    throw new IOException("Pipeline failed after 3 retries.", e);
  }
  retryCount++;
}
关键逻辑：
Client 自动移除失败 DN，向 NN 申请新 DN 补充 Pipeline；
重试次数默认 3 次，失败则向上层抛异常，保证写操作最终一致性。







重建 Pipeline 后再次写入，不会让之前成功的节点重复写入已成功的 Packet——HDFS 通过「Packet 唯一序号 + ACK 确认机制」精准定位未完成的 Packet，仅重试「失败的 Packet 段」，所有节点（包括之前成功的）只重写这部分失败数据，而非全量重传，既保证数据一致，又避免冗余。
以下基于 Hadoop 3.3.6 真实源码，拆解「防重复写入」的核心机制和重建后的写入流程：
一、核心前提：Packet 的唯一序号 + ACK 确认机制（防重的基础）
HDFS 将写入的 Block 数据拆分为固定大小的「Packet」（默认 64KB），每个 Packet 都有全局唯一、严格递增的序列号（seqno），Client 和 DataNode 均通过这个序号判断 “是否已写入”，这是防重复的核心。
1. Packet 序号的定义（源码）
源码路径：org/apache/hadoop/hdfs/DFSOutputStream.java
java
运行
// Packet是写入数据的最小单元，核心字段包含唯一序号
static class Packet {
  final long seqno; // Packet唯一序列号（Block内递增，如0,1,2...）
  final byte[] data; // 实际数据
  final int offsetInBlock; // Packet在Block中的偏移量
  boolean isLastPacket; // 是否为最后一个Packet
  
  public Packet(long seqno, int size, long offsetInBlock, boolean isLastPacket) {
    this.seqno = seqno;
    this.data = new byte[size];
    this.offsetInBlock = (int)offsetInBlock;
    this.isLastPacket = isLastPacket;
  }
}
关键逻辑：
每个 Block 的 Packet 序号从 0 开始递增（如 0→1→2→...），唯一标识该 Packet 在 Block 中的位置；
偏移量offsetInBlock和序号一一对应（如 seqno=0 对应偏移 0-65535，seqno=1 对应 65536-131071），DataNode 可通过序号快速定位写入位置。
2. Client 维护 ACK 确认列表（记录已成功的 Packet）
Client 会跟踪「所有 DataNode 已确认写入的最大序号（ACKedSeqno）」，只有未被 ACK 的 Packet 才会重试，源码如下：源码路径：org/apache/hadoop/hdfs/DFSOutputStream.java
java
运行
// DFSOutputStream核心字段：跟踪已确认的最大Packet序号
private long ackedSeqno = -1; // 初始为-1，表示无已确认Packet
private final LinkedBlockingQueue<Packet> dataQueue = new LinkedBlockingQueue<>(); // 待发送Packet队列

// 接收DataNode的ACK后，更新已确认序号
private void processAck(AckPacket ack) {
  synchronized (this) {
    long ackSeqno = ack.getSeqno();
    if (ackSeqno > ackedSeqno) {
      ackedSeqno = ackSeqno; // 更新为已确认的最大序号
    }
    // 移除队列中已确认的Packet（无需再重试）
    while (!dataQueue.isEmpty() && dataQueue.peek().seqno <= ackedSeqno) {
      dataQueue.poll();
    }
  }
}
关键逻辑：
ackedSeqno是核心：记录 “所有 DN 都已成功写入的最大 Packet 序号”；
比如前 3 个 Packet（seqno=0/1/2）都收到 ACK，ackedSeqno=2，只有 seqno≥3 的 Packet 会留在队列中。
二、重建 Pipeline 后：仅重试未 ACK 的 Packet（核心流程 + 源码）
当主 DN 宕机触发 Pipeline 重建后，Client 不会从头重发所有 Packet，而是从ackedSeqno+1开始，只重发未被确认的 Packet—— 之前成功的节点（比如从 DN1）收到这些 Packet 后，会通过序号校验跳过已写入的部分，仅写入未完成的。
1. 重建后重试的核心逻辑（源码）
源码路径：org/apache/hadoop/hdfs/DFSOutputStream.java
java
运行
// DataStreamer线程（负责发送Packet）的重启逻辑（重建Pipeline后调用）
void restart() {
  synchronized (this) {
    // 1. 重置发送状态，但保留未ACK的Packet队列
    this.currentSeqno = ackedSeqno + 1; // 从已确认序号的下一个开始重发
    this.lastPacketSent = null;
    this.isRunning = true;
  }
  
  // 2. 重新启动线程，发送队列中未ACK的Packet
  this.interrupt(); // 唤醒线程
  LOG.info("DataStreamer restarted, resuming from seqno: " + currentSeqno);
}

// DataStreamer发送Packet的核心循环
@Override
public void run() {
  while (isRunning) {
    try {
      Packet packet = dataQueue.take(); // 从队列取未ACK的Packet
      // 仅发送seqno ≥ currentSeqno的Packet（即未确认的）
      if (packet.seqno >= currentSeqno) {
        sendPacket(packet); // 发送给新的主DN
      }
    } catch (InterruptedException e) {
      continue;
    }
  }
}
关键逻辑：
重建后currentSeqno被设为ackedSeqno + 1，只发未确认的 Packet；
比如之前ackedSeqno=2，则只发 seqno=3、4、5... 的 Packet，seqno=0/1/2 的已确认 Packet 已被移出队列，不会重发。






2. DataNode 侧：按序号防重复写入（源码）
即使 Client 因异常重复发送了已确认的 Packet（比如网络延迟导致的误判），DataNode 也会通过序号校验跳过重复写入，源码如下：源码路径：org/apache/hadoop/hdfs/server/datanode/DataXceiver.java
java
运行
// DataNode写入Packet前的序号校验逻辑
private void writePacketWithSeqno(Block block, Packet packet) throws IOException {
  long seqno = packet.getSeqno();
  int offset = packet.getOffsetInBlock();
  
  // 1. 读取本地已写入的最大序号（持久化在Block的元数据文件中）
  BlockMetadataInfo meta = blockManager.getStoredBlock(block).getMetadata();
  long maxWrittenSeqno = meta.getMaxWrittenSeqno();
  
  // 2. 若Packet序号已写入，直接返回ACK，不重复写
  if (seqno <= maxWrittenSeqno) {
    LOG.info("Packet " + seqno + " already written, skip.");
    sendAckToClient(seqno); // 直接发ACK，不写入
    return;
  }
  
  // 3. 若序号未写入，执行正常写入（WAL + Block数据）
  writeToLocal(block, packet);
  // 更新本地已写入的最大序号
  meta.setMaxWrittenSeqno(seqno);
  // 发送ACK给Client
  sendAckToClient(seqno);
}
关键逻辑：
DataNode 会持久化每个 Block 的maxWrittenSeqno（已写入的最大 Packet 序号）；
收到 Packet 后先校验：序号≤已写入的→直接返回 ACK，不写数据；序号 > 已写入的→正常写入；
这是 “最后一道防线”，即使 Client 误发重复 Packet，DataNode 也能避免冗余写入。






























但它有功能完全等价的 “主 DataNode” 角色（对应 GFS 的 Primary ChunkServer），只是这个 “主” 不是通过 “选举” 产生，而是写流程中随 Pipeline 自动确定的，逻辑更简洁（避免额外协调开销）。
下面结合 Hadoop 3.3.6 源码，详细拆解 HDFS 中 “主 DataNode” 的「确定逻辑、核心职责、异常切换」，并对比 GFS 的 Primary 选举，帮你理清差异：
一、核心结论：HDFS 无 “选举”，但有 “默认主 DataNode”
GFS 的 Primary 是 Master 主动选举并颁发租期的独立角色；而 HDFS 的 “主 DataNode”（以下简称「主 DN」）是：
由 NameNode（NN）分配 Pipeline 时「按顺序指定」（不是选举）；
无独立租期，生命周期和当前 Block 的 Pipeline 绑定；
功能和 GFS Primary 完全一致：协调副本写入顺序、保证一致性。
二、HDFS 主 DataNode 的「确定逻辑 + 核心源码」
HDFS 的主 DN 是写流程中 “自动确定” 的，核心规则：NN 返回的 Pipeline 列表中，第一个 DataNode 即为 “主 DN”，Client 直接向其发起写请求，无需额外选举步骤。
1. 确定流程 + 源码拆解（写流程关联）
（1）NN 分配 Pipeline 时，按 “网络拓扑最优” 排序
NN 在 addBlock 时，通过 BlockPlacementPolicy 选择 3 个 DN（默认副本数），并按「Client 网络距离由近到远」排序（比如同机架 DN 排第一），这个排序直接决定主 DN：源码路径：org/apache/hadoop/hdfs/server/namenode/BlockPlacementPolicyDefault.java
java
运行
// NN 选择 Pipeline DN 列表的核心方法
@Override
public DatanodeInfo[] chooseTarget(int replication, DatanodeInfo[] excludedNodes,
                                  String[] favoredNodes, String src, INodeFile file) throws IOException {
  List<DatanodeInfo> targets = new ArrayList<>();
  // 1. 优先选同机架/近网络的 DN（Client 通信延迟最低）
  DatanodeInfo clientNode = getClientDatanode();
  DatanodeInfo sameRackNode = findSameRackNode(clientNode, excludedNodes);
  if (sameRackNode != null) {
    targets.add(sameRackNode); // 第一个 DN = 同机架节点（后续成为主 DN）
  } else {
    // 无同机架则选最近的跨机架节点
    targets.add(findClosestNode(clientNode, excludedNodes));
  }
  
  // 2. 补充剩余副本的 DN（跨机架，保证容错）
  while (targets.size() < replication) {
    DatanodeInfo newNode = findCrossRackNode(targets, excludedNodes);
    targets.add(newNode);
  }
  
  // 返回排序后的 Pipeline 列表：[主 DN, 从 DN1, 从 DN2]
  return targets.toArray(new DatanodeInfo[0]);
}
关键逻辑：
NN 返回的 Pipeline 列表「顺序固定」：第一个 DN 是 Client 网络延迟最低的（同机架优先），直接作为主 DN；
无任何 “选举投票” 步骤，NN 排序即 “指定主 DN”，逻辑比 GFS 简单。
（2）Client 绑定主 DN，发起写请求
Client 拿到 NN 返回的 Pipeline 列表后，直接将第一个 DN 作为主 DN，建立 TCP 连接并发送数据：源码路径：org/apache/hadoop/hdfs/DFSOutputStream.java
java
运行
// Client 建立 Pipeline 并绑定主 DN 的核心逻辑
void setPipeline(DatanodeInfo[] targets) throws IOException {
  this.pipeline = new LinkedList<>();
  this.mainDatanode = targets[0]; // 直接绑定第一个 DN 为主 DN
  Socket mainSock = null;
  
  try {
    // 1. 先和主 DN 建立连接（核心：所有数据先发给主 DN）
    mainSock = new Socket(mainDatanode.getIpAddr(), mainDatanode.getXferPort());
    this.mainSock = mainSock;
    pipeline.add(mainSock);
    
    // 2. 再和从 DN 建立连接（主 DN 后续会转发数据）
    for (int i = 1; i < targets.length; i++) {
      Socket sock = new Socket(targets[i].getIpAddr(), targets[i].getXferPort());
      pipeline.add(sock);
    }
    
    // 3. 向主 DN 发送 Pipeline 元数据（告知其主角色+从 DN 列表）
    sendPipelineMetadata(mainSock, targets);
  } catch (IOException e) {
    // 连接失败则重建 Pipeline
    closeAllSockets();
    throw e;
  }
}

// 向主 DN 发送元数据：明确其主角色和从 DN 列表
private void sendPipelineMetadata(Socket mainSock, DatanodeInfo[] targets) throws IOException {
  PipelineMetadata meta = new PipelineMetadata();
  meta.setMainDatanode(true); // 标记该 DN 为主
  meta.setSlaveDatanodes(Arrays.copyOfRange(targets, 1, targets.length)); // 从 DN 列表
  // 写入流，主 DN 接收后知晓自身角色
  ObjectOutputStream out = new ObjectOutputStream(mainSock.getOutputStream());
  out.writeObject(meta);
  out.flush();
}
关键逻辑：
Client 直接将 Pipeline 第一个 DN 标记为 mainDatanode，无任何 “协商”；
通过 sendPipelineMetadata 告知主 DN：“你是主，需要协调从 DN 写入”。
2. 主 DataNode 的核心职责（和 GFS Primary 一致）
主 DN 拿到 Client 数据后，承担「顺序协调、数据转发、确认反馈」的核心职责，保证所有副本写入一致：源码路径：org/apache/hadoop/hdfs/server/datanode/DataXceiver.java
java
运行
// 主 DN 处理写请求的核心逻辑（接收 Client 数据后）
private void processWriteBlockAsMain(PipelineMetadata meta, DataInputStream in) throws IOException {
  Block block = new Block(header.getBlockId());
  List<DatanodeInfo> slaveDNs = meta.getSlaveDatanodes();
  List<Socket> slaveSocks = new ArrayList<>();
  
  // 1. 先和所有从 DN 建立转发连接（主 DN 主动发起）
  for (DatanodeInfo slave : slaveDNs) {
    Socket sock = new Socket(slave.getIpAddr(), slave.getXferPort());
    slaveSocks.add(sock);
  }
  
  // 2. 读取 Client 发送的 Packet（按顺序）
  byte[] packet = new byte[header.getPacketLength()];
  while (in.readFully(packet) != -1) {
    // 3. 先写本地 WAL + Block 数据（保证自身数据完整）
    writeToLocal(block, packet);
    
    // 4. 按顺序转发 Packet 给从 DN（保证所有副本写入顺序一致）
    for (Socket slaveSock : slaveSocks) {
      OutputStream out = slaveSock.getOutputStream();
      out.write(packet);
      out.flush();
    }
    
    // 5. 等待所有从 DN 的写入确认（ACK）
    boolean allAcked = waitForSlaveAcks(slaveSocks);
    if (!allAcked) {
      throw new IOException("Slave DN write failed.");
    }
  }
  
  // 6. 向 Client 发送最终确认（所有副本写入成功）
  sendFinalAckToClient(mainSock);
}

// 本地写入逻辑：先写 WAL，再写 Block 数据（和 GFS 一致）
private void writeToLocal(Block block, byte[] packet) throws IOException {
  File walFile = new File(getBlockDir(block), block.getBlockId() + ".wal");
  try (FileOutputStream walOut = new FileOutputStream(walFile, true)) {
    walOut.write(packet); // 写前日志，防止宕机丢失
  }
  try (FileOutputStream blockOut = new FileOutputStream(getBlockFile(block), true)) {
    blockOut.write(packet); // 写 Block 数据
  }
}
关键职责（和 GFS Primary 对齐）：
顺序协调：按 Client 发送的 Packet 顺序转发给从 DN，避免数据交错；
数据转发：Client 只需要给主 DN 发一次数据，主 DN 负责转发给所有从 DN（减少 Client 网络开销）；
确认反馈：只有所有从 DN 写入成功并 ACK，主 DN 才向 Client 返回成功。







