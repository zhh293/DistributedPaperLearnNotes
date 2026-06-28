# Docker Compose部署Redis一主三从集群（四台服务器）及Spring Boot连接指南

# 一、环境说明

## 1.1 服务器信息（必配置）

假设四台服务器均为Linux系统（CentOS/Ubuntu通用，差异处会标注），每台服务器有独立公网IP，规划如下：

|服务器序号|角色|公网IP|内网IP（可选，优先用公网）|需开放端口|
|---|---|---|---|---|
|Server-1|Redis主节点（master）|X.X.X.X（替换为实际公网IP）|如192.168.0.101（无则忽略）|6379（Redis端口）、26379（哨兵端口，可选）|
|Server-2|Redis从节点（slave-1）|Y.Y.Y.Y（替换为实际公网IP）|如192.168.0.102（无则忽略）|6379、26379|
|Server-3|Redis从节点（slave-2）|Z.Z.Z.Z（替换为实际公网IP）|如192.168.0.103（无则忽略）|6379、26379|
|Server-4|Redis从节点（slave-3）|W.W.W.W（替换为实际公网IP）|如192.168.0.104（无则忽略）|6379、26379|
## 1.2 依赖软件

- Docker：20.10.0+

- Docker Compose：2.0.0+

- JDK：1.8+（Spring Boot项目用）

- Spring Boot：2.3.0+（适配Redis集群配置）

# 二、四台服务器统一环境准备（每台都要执行）

## 2.1 关闭防火墙/开放端口（公网环境必做）

方式一：临时关闭防火墙（测试环境用，重启失效）

```shell

# CentOS系统
systemctl stop firewalld
systemctl disable firewalld

# Ubuntu系统
ufw disable
ufw status
```

方式二：开放指定端口（生产环境推荐，更安全）

```shell

# CentOS系统（firewalld）
firewall-cmd --permanent --add-port=6379/tcp
firewall-cmd --permanent --add-port=26379/tcp
firewall-cmd --reload
firewall-cmd --list-ports  # 验证端口是否开放

# Ubuntu系统（ufw）
ufw allow 6379/tcp
ufw allow 26379/tcp
ufw reload
ufw status  # 验证端口是否开放
```

## 2.2 安装Docker

```shell

# 卸载旧版本Docker（如有）
yum remove docker docker-client docker-client-latest docker-common docker-latest docker-latest-logrotate docker-logrotate docker-engine  # CentOS
apt-get remove docker docker-engine docker.io containerd runc  # Ubuntu

# 安装依赖包
yum install -y yum-utils device-mapper-persistent-data lvm2  # CentOS
apt-get update && apt-get install -y ca-certificates curl gnupg lsb-release  # Ubuntu

# 添加Docker官方仓库
yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo  # CentOS
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null  # Ubuntu

# 安装Docker CE
yum install -y docker-ce docker-ce-cli containerd.io  # CentOS
apt-get update && apt-get install -y docker-ce docker-ce-cli containerd.io  # Ubuntu

# 启动Docker并设置开机自启
systemctl start docker
systemctl enable docker

# 验证Docker安装成功
docker --version  # 输出Docker版本即成功
docker run hello-world  # 运行测试容器，无报错即正常
```

## 2.3 安装Docker Compose

```shell

# 下载Docker Compose二进制文件
curl -L "https://github.com/docker/compose/releases/download/v2.20.2/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose

# 赋予执行权限
chmod +x /usr/local/bin/docker-compose

# 建立软链接（避免命令找不到）
ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose

# 验证安装成功
docker-compose --version  # 输出版本即成功
```

## 2.4 关闭SELinux（CentOS系统必做，Ubuntu忽略）

```shell

# 临时关闭（重启失效）
setenforce 0

# 永久关闭（修改配置文件，重启生效）
vi /etc/selinux/config
# 将SELINUX=enforcing改为SELINUX=disabled
# 保存退出后，重启服务器：reboot
```

# 三、部署Redis一主三从集群（按服务器分别操作）

核心逻辑：每台服务器用Docker Compose启动一个Redis节点，通过配置指定主从关系，主节点开放写入权限，从节点仅同步主节点数据并提供读取权限。

## 3.1 主节点部署（Server-1，IP：X.X.X.X）

### 步骤1：创建目录结构（规范文件存放）

```shell

mkdir -p /usr/local/redis/master/{conf,data,logs}
cd /usr/local/redis/master  # 进入工作目录
```

### 步骤2：编写Redis配置文件（redis.conf）

```shell

vi conf/redis.conf
```

粘贴以下内容（关键配置已标注注释，无需修改，仅需确认密码）：

```ini

# 基础配置
bind 0.0.0.0  # 允许所有IP访问（公网环境需配合密码，否则有安全风险）
port 6379  # Redis端口
daemonize no  # 不后台运行（Docker容器内无需后台，由容器管理）
pidfile /var/run/redis.pid
logfile /usr/local/redis/logs/redis.log  # 日志路径
databases 16  # 数据库数量
requirepass 123456  # 访问密码（必填，记好，Spring Boot和从节点都要用到）
masterauth 123456  # 主从同步密码（需和requirepass一致，从节点连接主节点用）

# 主节点配置（固定为主节点）
slaveof no one  # 不跟随任何节点（自身为主节点）
appendonly yes  # 开启AOF持久化（保证数据不丢失）
appendfilename "appendonly.aof"
appendfsync everysec  # 每秒同步一次AOF文件
dir /usr/local/redis/data  # 数据存储路径

# 公网集群优化配置
protected-mode no  # 关闭保护模式（公网IP访问必须关）
tcp-keepalive 300  # 保持TCP连接，避免公网连接断开
timeout 0  # 连接超时时间（0表示无限制）
```

### 步骤3：编写Docker Compose文件（docker-compose.yml）

```shell

vi docker-compose.yml
```

粘贴以下内容：

```yaml

version: '3.8'
services:
  redis-master:
    image: redis:6.2.7  # 选用稳定版Redis镜像（避免最新版兼容问题）
    container_name: redis-master  # 容器名称
    restart: always  # 容器异常自动重启
    ports:
      - "6379:6379"  # 端口映射（宿主机:容器）
    volumes:
      - ./conf/redis.conf:/usr/local/etc/redis/redis.conf  # 配置文件挂载
      - ./data:/usr/local/redis/data  # 数据目录挂载（持久化数据）
      - ./logs:/usr/local/redis/logs  # 日志目录挂载
    command: redis-server /usr/local/etc/redis/redis.conf  # 启动命令（指定配置文件）
    network_mode: "host"  # 采用主机网络模式（公网环境下节点通信更稳定，避免端口映射问题）
    environment:
      - TZ=Asia/Shanghai  # 时区设置（和宿主机一致）
```

### 步骤4：启动主节点容器

```shell

# 启动容器（后台运行）
docker-compose up -d

# 验证容器是否启动成功
docker-compose ps  # 状态为Up即正常
docker logs redis-master  # 查看日志，无报错即可
```

## 3.2 从节点部署（Server-2/3/4，操作完全一致，仅示例Server-2）

### 步骤1：创建目录结构

```shell

mkdir -p /usr/local/redis/slave/{conf,data,logs}
cd /usr/local/redis/slave  # 进入工作目录
```

### 步骤2：编写Redis配置文件（redis.conf）

```shell

vi conf/redis.conf
```

粘贴以下内容（重点修改slaveof参数，指向主节点公网IP）：

```ini

# 基础配置（和主节点一致）
bind 0.0.0.0
port 6379
daemonize no
pidfile /var/run/redis.pid
logfile /usr/local/redis/logs/redis.log
databases 16
requirepass 123456  # 必须和主节点密码一致
masterauth 123456  # 必须和主节点密码一致

# 从节点配置（关键：指向主节点）
slaveof X.X.X.X 6379  # 替换为【主节点公网IP】和端口（6379）
slave-read-only yes  # 从节点设为只读（禁止写入，保证主从一致）
appendonly yes
appendfilename "appendonly.aof"
appendfsync everysec
dir /usr/local/redis/data

# 公网集群优化配置（和主节点一致）
protected-mode no
tcp-keepalive 300
timeout 0
```

### 步骤3：编写Docker Compose文件（docker-compose.yml）

```shell

vi docker-compose.yml
```

粘贴以下内容（和主节点的Docker Compose文件仅容器名称不同）：

```yaml

version: '3.8'
services:
  redis-slave:
    image: redis:6.2.7
    container_name: redis-slave  # 容器名称（区分主节点）
    restart: always
    ports:
      - "6379:6379"
    volumes:
      - ./conf/redis.conf:/usr/local/etc/redis/redis.conf
      - ./data:/usr/local/redis/data
      - ./logs:/usr/local/redis/logs
    command: redis-server /usr/local/etc/redis/redis.conf
    network_mode: "host"
    environment:
      - TZ=Asia/Shanghai
```

### 步骤4：启动从节点容器

```shell

# 启动容器
docker-compose up -d

# 验证启动状态
docker-compose ps
docker logs redis-slave  # 日志中出现"Slaveof X.X.X.X:6379 enabled"即成功
```

⚠️ 注意：Server-3、Server-4重复执行3.2节所有步骤，确保slaveof参数均指向主节点（X.X.X.X）的公网IP。

# 四、Redis集群验证（确保主从同步正常）

## 4.1 验证主节点状态

```shell

# 进入主节点容器内部
docker exec -it redis-master redis-cli

# 输入密码登录
auth 123456

# 查看主节点信息
info replication
```

正常输出结果（关键信息）：

```text

# Replication
role:master  # 角色为主节点
connected_slaves:3  # 已连接3个从节点（说明三个从节点均连接成功）
slave0:ip=Y.Y.Y.Y,port=6379,state=online,offset=xxx,lag=0  # Server-2从节点
slave1:ip=Z.Z.Z.Z,port=6379,state=online,offset=xxx,lag=0  # Server-3从节点
slave2:ip=W.W.W.W,port=6379,state=online,offset=xxx,lag=0  # Server-4从节点
```

## 4.2 验证主从同步功能

### 步骤1：主节点写入数据

```shell

# 主节点容器内执行（已登录Redis）
set test_key "redis-master-slave-test"
get test_key  # 输出"redis-master-slave-test"即写入成功
```

### 步骤2：从节点读取数据（以Server-2为例）

```shell

# 进入Server-2的从节点容器
docker exec -it redis-slave redis-cli

# 输入密码登录
auth 123456

# 读取主节点写入的数据
get test_key  # 输出"redis-master-slave-test"即同步成功

# 尝试从节点写入数据（验证只读权限）
set test_key2 "slave-write-test"  # 报错"READONLY You can't write against a read only replica."，说明只读配置生效
```

⚠️ 验证完后，Server-3、Server-4也可执行相同读取操作，确保所有从节点均能同步主节点数据。

# 五、Spring Boot项目连接Redis集群（主从模式）

## 5.1 引入Redis依赖（pom.xml）

如果是Maven项目，在pom.xml中添加以下依赖：

```xml

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<!-- 连接池依赖（优化Redis连接性能） -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

## 5.2 配置Redis集群（application.yml/application.properties）

推荐用yml格式，在src/main/resources下编写application.yml：

```yaml

spring:
  redis:
    # Redis访问密码（必须和集群密码一致）
    password: 123456
    # 连接池配置（优化性能，可选但推荐）
    lettuce:
      pool:
        max-active: 8  # 最大连接数
        max-idle: 8    # 最大空闲连接数
        min-idle: 2    # 最小空闲连接数
        max-wait: 1000ms  # 连接等待时间
    # 主从集群配置
    sentinel:
      # 哨兵模式（可选，用于主节点故障自动切换，下文会补充哨兵部署）
      # 若暂时不需要自动切换，可跳过哨兵，直接配置主从节点
      master: redis-master  # 主节点名称（和哨兵配置一致）
      nodes:  # 所有节点的公网IP:端口（包括主节点和从节点）
        - X.X.X.X:26379  # 主节点哨兵端口
        - Y.Y.Y.Y:26379  # 从节点1哨兵端口
        - Z.Z.Z.Z:26379  # 从节点2哨兵端口
        - W.W.W.W:26379  # 从节点3哨兵端口
    # 非哨兵模式（仅基础主从，无自动切换，适合测试环境）
    # cluster:
    #   nodes:
    #     - X.X.X.X:6379
    #     - Y.Y.Y.Y:6379
    #     - Z.Z.Z.Z:6379
    #     - W.W.W.W:6379
    #   max-redirects: 3  # 最大重定向次数

# RedisTemplate配置（可选，自定义序列化方式，避免存乱码）
redis:
  key-prefix: "springboot:redis:"  # key前缀
  expire-default: 86400  # 默认过期时间（秒）
  enable-cache: true  # 开启缓存
```

## 5.3 补充哨兵部署（实现主节点故障自动切换，生产环境必做）

主从模式下，若主节点故障，从节点无法自动切换为主节点，需部署哨兵（Sentinel）实现高可用。每台服务器均部署一个哨兵，共4个哨兵。

### 步骤1：主节点（Server-1）部署哨兵

```shell

# 创建哨兵目录
mkdir -p /usr/local/redis/sentinel/conf
cd /usr/local/redis/sentinel

# 编写哨兵配置文件（sentinel.conf）
vi conf/sentinel.conf
```

粘贴以下内容：

```ini

port 26379  # 哨兵端口
daemonize no  # 不后台运行
logfile "/usr/local/redis/logs/sentinel.log"
sentinel monitor redis-master X.X.X.X 6379 2  # 监控主节点（名称：redis-master，IP：主节点公网IP，端口：6379，投票数：2）
sentinel auth-pass redis-master 123456  # 主节点密码（和Redis密码一致）
sentinel down-after-milliseconds redis-master 30000  # 30秒无响应则认为主节点故障
sentinel parallel-syncs redis-master 1  # 故障切换后，同时同步数据的从节点数量
sentinel failover-timeout redis-master 180000  # 故障切换超时时间（180秒）
protected-mode no  # 关闭保护模式（公网访问）
bind 0.0.0.0  # 允许所有IP访问
```

### 步骤2：主节点哨兵Docker Compose配置（新增到master的docker-compose.yml）

```shell

cd /usr/local/redis/master
vi docker-compose.yml
```

添加哨兵服务（整体内容如下）：

```yaml

version: '3.8'
services:
  redis-master:
    # 原有主节点配置（不变）
    image: redis:6.2.7
    container_name: redis-master
    restart: always
    ports:
      - "6379:6379"
    volumes:
      - ./conf/redis.conf:/usr/local/etc/redis/redis.conf
      - ./data:/usr/local/redis/data
      - ./logs:/usr/local/redis/logs
    command: redis-server /usr/local/etc/redis/redis.conf
    network_mode: "host"
    environment:
      - TZ=Asia/Shanghai

  redis-sentinel:
    # 新增哨兵服务
    image: redis:6.2.7
    container_name: redis-sentinel-master
    restart: always
    ports:
      - "26379:26379"
    volumes:
      - /usr/local/redis/sentinel/conf/sentinel.conf:/usr/local/etc/redis/sentinel.conf
      - /usr/local/redis/master/logs:/usr/local/redis/logs  # 共享日志目录
    command: redis-sentinel /usr/local/etc/redis/sentinel.conf  # 启动哨兵
    network_mode: "host"
    environment:
      - TZ=Asia/Shanghai
    depends_on:
      - redis-master  # 依赖主节点，主节点启动后再启动哨兵
```

### 步骤3：从节点（Server-2/3/4）部署哨兵

每台从节点的哨兵配置和主节点一致，仅容器名称不同，步骤如下（以Server-2为例）：

```shell

# 创建哨兵目录
mkdir -p /usr/local/redis/sentinel/conf
cd /usr/local/redis/sentinel

# 编写哨兵配置文件（和主节点的sentinel.conf完全一致）
vi conf/sentinel.conf  # 内容复制主节点的sentinel.conf

# 编辑从节点的docker-compose.yml，添加哨兵服务
cd /usr/local/redis/slave
vi docker-compose.yml
```

添加哨兵服务（整体内容如下）：

```yaml

version: '3.8'
services:
  redis-slave:
    # 原有从节点配置（不变）
    image: redis:6.2.7
    container_name: redis-slave
    restart: always
    ports:
      - "6379:6379"
    volumes:
      - ./conf/redis.conf:/usr/local/etc/redis/redis.conf
      - ./data:/usr/local/redis/data
      - ./logs:/usr/local/redis/logs
    command: redis-server /usr/local/etc/redis/redis.conf
    network_mode: "host"
    environment:
      - TZ=Asia/Shanghai

  redis-sentinel:
    # 新增哨兵服务
    image: redis:6.2.7
    container_name: redis-sentinel-slave1  # 每个从节点哨兵容器名称区分（slave1/slave2/slave3）
    restart: always
    ports:
      - "26379:26379"
    volumes:
      - /usr/local/redis/sentinel/conf/sentinel.conf:/usr/local/etc/redis/sentinel.conf
      - /usr/local/redis/slave/logs:/usr/local/redis/logs
    command: redis-sentinel /usr/local/etc/redis/sentinel.conf
    network_mode: "host"
    environment:
      - TZ=Asia/Shanghai
    depends_on:
      - redis-slave  # 依赖从节点
```

### 步骤4：启动所有哨兵容器

```shell

# 主节点（Server-1）
cd /usr/local/redis/master
docker-compose up -d  # 重启主节点容器，同时启动哨兵

# 从节点（Server-2/3/4）
cd /usr/local/redis/slave
docker-compose up -d  # 重启从节点容器，同时启动哨兵

# 验证哨兵状态（任意一台服务器执行）
docker exec -it redis-sentinel-master redis-cli -p 26379
sentinel master redis-master  # 查看监控的主节点信息，无报错即正常
```

## 5.4 编写Redis工具类（可选，简化操作）

创建RedisUtils.java，封装Redis的常用操作（如set、get、delete等）：

```java

package com.example.demo.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class RedisUtils {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 存入数据
    public boolean set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // 存入数据并设置过期时间
    public boolean set(String key, Object value, long time) {
        try {
            if (time > 0) {
                redisTemplate.opsForValue().set(key, value, time, TimeUnit.SECONDS);
            } else {
                set(key, value);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // 获取数据
    public Object get(String key) {
        return key == null ? null : redisTemplate.opsForValue().get(key);
    }

    // 删除数据
    public boolean delete(String key) {
        return redisTemplate.delete(key);
    }
}
```

## 5.5 测试Spring Boot连接Redis集群

创建测试接口，验证是否能正常读写Redis：

```java

package com.example.demo.controller;

import com.example.demo.utils.RedisUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedisTestController {

    @Autowired
    private RedisUtils redisUtils;

    @GetMapping("/redis/set")
    public String setKey(@RequestParam String key, @RequestParam String value) {
        boolean result = redisUtils.set(key, value);
        return result ? "设置成功" : "设置失败";
    }

    @GetMapping("/redis/get")
    public Object getKey(@RequestParam String key) {
        return redisUtils.get(key);
    }
}
```

启动Spring Boot项目，通过接口测试：

- 设置数据：http://localhost:8080/redis/set?key=springboot_test&value=success

- 获取数据：http://localhost:8080/redis/get?key=springboot_test

若能正常返回“success”，说明Spring Boot已成功连接Redis集群。

# 六、常见问题排查

## 6.1 从节点无法连接主节点

- 检查主节点防火墙是否开放6379端口，或是否关闭防火墙。

- 确认从节点redis.conf中slaveof参数是否正确（主节点公网IP和端口）。

- 检查主从节点的requirepass和masterauth密码是否一致。

- 查看从节点日志：docker logs redis-slave，排查具体报错信息。

## 6.2 Spring Boot连接Redis报错“Could not get a resource from the pool”

- 检查Redis密码是否正确（application.yml中的password和Redis配置一致）。

- 确认Redis集群所有节点的公网IP和端口是否能被Spring Boot项目所在服务器访问（可通过ping、telnet测试）。

- 检查Redis连接池配置是否合理，若并发量高，可适当增大max-active参数。

## 6.3 主节点故障后，哨兵未触发自动切换

- 检查哨兵配置文件中sentinel monitor的投票数（2）是否小于等于哨兵数量（4）。

- 确认所有哨兵容器均正常运行（docker-compose ps查看状态）。

- 查看哨兵日志：docker logs redis-sentinel-master，排查故障原因。

# 七、文档说明

1. 本文档步骤适用于Linux系统（CentOS/Ubuntu），Windows服务器需调整Docker安装和目录路径命令。

2. 所有配置文件中的密码（123456）需替换为实际生产环境的强密码，避免安全风险。

3. 公网环境下，建议给Redis节点配置安全组，仅允许Spring Boot项目所在服务器的IP访问，进一步提升安全性。

4. 若需扩展Redis集群（如增加从节点），仅需重复从节点部署步骤，哨兵会自动识别新节点。
> （注：文档部分内容可能由 AI 生成）