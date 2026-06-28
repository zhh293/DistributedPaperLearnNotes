#include <stdio.h>
#include <Windows.h>
#include <process.h>
#include <string.h>

/*
* 编译
* gcc -o main main.cpp -lwsock32 -lstdc++
*/

#pragma comment(lib, "Ws2_32.lib")

#define MAXSIZE 65507  // 发送数据报⽂的最⼤⻓度
#define HTTP_PORT 80   // http 服务器端⼝
#define CACHESIZE 1024 // cache大小

#define MASK_SITE "..."             // 屏蔽网站
#define PHISHING_SITE "..."         // 钓鱼主机
#define CACHE_SITE "..."            // cache测试网站
#define PHISHING_SITE_SRC "..."     // 钓鱼原网址
#define PHISHING_SITE_DST "..."     // 钓鱼目标网址

// Http 重要头部数据
struct HttpHeader
{
    char method[4];         // POST 或者GET，注意有些为CONNECT，本实验暂不考虑
    char url[1024];         // 请求的url
    char host[1024];        // ⽬标主机
    char cookie[1024 * 10]; // cookie
    HttpHeader()
    {
        ZeroMemory(this, sizeof(HttpHeader)); // 初始化HttpHeader
    }
};

// 代理参数
struct ProxyParam
{
    SOCKET clientSocket;
    SOCKET serverSocket;
};

// Http 缓存
struct HttpCache
{
    char url[1024];         // 请求的url
    char host[1024];        // ⽬标主机
    char lastModified[100]; // 最后一次修改时间
    char code[4];           // 状态码
    char buffer[MAXSIZE];   // 数据报文
    HttpCache()
    {
        ZeroMemory(this, sizeof(HttpCache)); // 初始化HttpCache
    }
};

HttpCache Cache[CACHESIZE];
// cache相关参数
int cachePtr = 0; // cache指针
int cacheNum = 0; // cache总数

// 代理相关参数
SOCKET ProxyServer;
sockaddr_in ProxyServerAddr;
const int ProxyPort = 10240;

BOOL InitSocket();
int ParseHttpHead(char *buffer, HttpHeader *httpHeader);
void ParseCache(char *buffer, char *code, char *lastModified);
BOOL ConnectToServer(SOCKET *serverSocket, char *host);
unsigned int __stdcall ProxyThread(LPVOID lpParameter);

// 由于新的连接都使⽤新线程进⾏处理，对线程的频繁的创建和销毁特别浪费资源
// 可以使⽤线程池技术提⾼服务器效率
// const int ProxyThreadMaxNum = 20;
// HANDLE ProxyThreadHandle[ProxyThreadMaxNum] = {0};
// DWORD ProxyThreadDW[ProxyThreadMaxNum] = {0};
int main(int argc, char **argv)
{
    printf("代理服务器正在启动\n");
    printf("初始化...\n");
    if (!InitSocket())
    {
        printf("socket 初始化失败\n");
        return -1;
    }
    printf("代理服务器正在运行，监听端口 %d\n", ProxyPort);
    SOCKET acceptSocket = INVALID_SOCKET;
    SOCKADDR_IN acceptAddr;
    ProxyParam *lpProxyParam;
    HANDLE hThread;
    DWORD dwThreadID;

    // 代理服务器循环监听
    while (true)
    {
        acceptSocket = accept(ProxyServer, (SOCKADDR *)&acceptAddr, NULL);

        lpProxyParam = new ProxyParam;
        if (lpProxyParam == NULL)
        {
            continue;
        }
        lpProxyParam->clientSocket = acceptSocket;
        hThread = (HANDLE)_beginthreadex(NULL, 0, &ProxyThread, (LPVOID)lpProxyParam, 0, 0);
        CloseHandle(hThread);
        Sleep(200);
    }
    closesocket(ProxyServer);
    WSACleanup();
    return 0;
}

//************************************
// Method: InitSocket
// FullName: InitSocket
// Access: public
// Returns: BOOL
// Qualifier: 初始化套接字
//************************************
BOOL InitSocket()
{
    // 加载套接字库（必须）
    WORD wVersionRequested;
    WSADATA wsaData;
    // 套接字加载时错误提示
    int err;
    // 版本2.2
    wVersionRequested = MAKEWORD(2, 2);
    // 加载dll ⽂件Scoket 库
    err = WSAStartup(wVersionRequested, &wsaData);
    if (err != 0)
    {
        // 找不到winsock.dll
        printf("加载winsock 失败，错误代码为: %d\n", WSAGetLastError());
        return FALSE;
    }
    if (LOBYTE(wsaData.wVersion) != 2 || HIBYTE(wsaData.wVersion) != 2)
    {
        // winsock.dll版本错误
        printf("不能找到正确的winsock 版本\n");
        WSACleanup();
        return FALSE;
    }
    // 创建TCP/IP流套接字
    ProxyServer = socket(AF_INET, SOCK_STREAM, 0);
    if (INVALID_SOCKET == ProxyServer)
    {
        printf("创建套接字失败，错误代码为：%d\n", WSAGetLastError());
        return FALSE;
    }
    // IPv4
    ProxyServerAddr.sin_family = AF_INET;
    // 端口10240
    ProxyServerAddr.sin_port = htons(ProxyPort);

    // // 所有地址均可连接
    // ProxyServerAddr.sin_addr.S_un.S_addr = INADDR_ANY;
    // 只有本机地址可以连接
    ProxyServerAddr.sin_addr.S_un.S_addr = htonl(INADDR_LOOPBACK);
    // 绑定地址与端口号到套接字
    if (bind(ProxyServer, (SOCKADDR *)&ProxyServerAddr, sizeof(SOCKADDR)) == SOCKET_ERROR)
    {
        printf("绑定套接字失败\n");
        return FALSE;
    }
    if (listen(ProxyServer, SOMAXCONN) == SOCKET_ERROR)
    {
        printf("监听端⼝%d 失败", ProxyPort);
        return FALSE;
    }
    return TRUE;
}

//************************************
// Method: ProxyThread
// FullName: ProxyThread
// Access: public
// Returns: unsigned int __stdcall
// Qualifier: 线程执⾏函数
// Parameter: LPVOID lpParameter
//************************************
unsigned int __stdcall ProxyThread(LPVOID lpParameter)
{
    char Buffer[MAXSIZE];
    char phishBuffer[MAXSIZE]; // 钓鱼
    char cacheBuffer[MAXSIZE]; // cache
    char *CacheBuffer;

    ZeroMemory(Buffer, MAXSIZE);
    ZeroMemory(phishBuffer, MAXSIZE);
    ZeroMemory(cacheBuffer, MAXSIZE);

    SOCKADDR_IN clientAddr;
    int length = sizeof(SOCKADDR_IN);
    int recvSize;
    int ret;
    int cachePos;

    // 接收客户端请求，存到Buffer
    recvSize = recv(((ProxyParam *)lpParameter)->clientSocket, Buffer, MAXSIZE, 0);

    HttpHeader *httpHeader = new HttpHeader();
    CacheBuffer = new char[recvSize + 1];
    ZeroMemory(CacheBuffer, recvSize + 1);
    memcpy(CacheBuffer, Buffer, recvSize);
    cachePos = ParseHttpHead(CacheBuffer, httpHeader);
    delete CacheBuffer;

    // 钓鱼网站
    if (strcmp(httpHeader->url, PHISHING_SITE_SRC) == 0)
    {
        strcpy(httpHeader->host, PHISHING_SITE);
    }

    // 主机连接代理
    if (!ConnectToServer(&((ProxyParam *)lpParameter)->serverSocket, httpHeader->host))
    {
        printf("代理连接主机%s 失败\n", httpHeader->host);
        goto error;
    }
    printf("代理连接主机%s 成功\n", httpHeader->host);

    // 屏蔽网站
    if (strcmp(httpHeader->url, MASK_SITE) == 0)
    {
        printf("代理已经屏蔽网站%s\n", httpHeader->url);
        goto error;
    }

    // 网站钓鱼
    if (strcmp(httpHeader->url, PHISHING_SITE_SRC) == 0)
    {
        char *p;
        printf("网站 %s 已被成功重定向至 %s\n", PHISHING_SITE_SRC, PHISHING_SITE_DST);

        // 构造报文头
        char requestLine[] = "GET / HTTP/1.1\r\n";
        char host[] = "Host: www.baidu.com\r\n";
        char connection[] = "Connection: keep-alive\r\n";
        char accept[] = "Accept: */*\r\n";
        char userAgent[] = "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3\r\n";
        char acceptLanguage[] = "Accept-Language: en-US,en;q=0.9\r\n";
        char end[] = "\r\n"; // 添加请求头结束标志

        memcpy(phishBuffer, requestLine, strlen(requestLine));
        p = phishBuffer + strlen(requestLine);
        memcpy(p, host, strlen(host));
        p += strlen(host);
        memcpy(p, connection, strlen(connection));
        p += strlen(connection);
        memcpy(p, accept, strlen(accept));
        p += strlen(accept);
        memcpy(p, userAgent, strlen(userAgent));
        p += strlen(userAgent);
        memcpy(p, acceptLanguage, strlen(acceptLanguage));
        p += strlen(acceptLanguage);
        memcpy(p, end, strlen(end)); // 将结束标志添加到请求报文中
        p += strlen(end);

        // 将客户端发送的 HTTP 数据报文直接转发给目标服务器
        ret = send(((ProxyParam *)lpParameter)->serverSocket, phishBuffer, strlen(phishBuffer), 0);
        // 等待目标服务器返回数据
        recvSize = recv(((ProxyParam *)lpParameter)->serverSocket, phishBuffer, MAXSIZE, 0);

        // 返回重定向报文给客户端
        ret = send(((ProxyParam *)lpParameter)->clientSocket, phishBuffer, sizeof(phishBuffer), 0);
        goto error;
    }

    // 有cache
    if (cachePos != -1)
    {
        char *p;
        char lastModified[100];
        char code[4];

        // 构造缓存报文头
        memcpy(cacheBuffer, Buffer, recvSize);
        p = cacheBuffer + recvSize;
        memcpy(p, "If-modified-since: ", 19);
        p += 19;
        memcpy(p, Cache[cachePos].lastModified, strlen(Cache[cachePos].lastModified));
        p += strlen(Cache[cachePos].lastModified);

        // 将客户端发送的 HTTP 数据报文直接转发给目标服务器
        ret = send(((ProxyParam *)lpParameter)->serverSocket, cacheBuffer, strlen(cacheBuffer) + 1, 0);
        // 等待目标服务器返回数据
        recvSize = recv(((ProxyParam *)lpParameter)->serverSocket, cacheBuffer, MAXSIZE, 0);
        if (recvSize <= 0)
        {
            goto error;
        }

        // 解析包含缓存信息的HTTP报文头
        CacheBuffer = new char[recvSize + 1];
        ZeroMemory(CacheBuffer, recvSize + 1);
        memcpy(CacheBuffer, cacheBuffer, recvSize);
        ParseCache(CacheBuffer, code, lastModified);
        delete CacheBuffer;

        // 分析是否需要更新cache
        if (strcmp(code, "304") == 0)
        {
            // 状态码304标识没被修改
            printf("缓存URL: %s\n", Cache[cachePos].url);
            // 将缓存数据转发给客户端
            ret = send(((ProxyParam *)lpParameter)->clientSocket, Cache[cachePos].buffer, sizeof(Cache[cachePos].buffer), 0);
            if (ret != SOCKET_ERROR)
                printf("由缓存发送\n");
        }
        else if (strcmp(code, "200") == 0)
        {
            // 状态码200标识被修改了
            printf("缓存URL: %s\n", Cache[cachePos].url);
            memcpy(Cache[cachePos].buffer, cacheBuffer, strlen(cacheBuffer));
            memcpy(Cache[cachePos].lastModified, lastModified, strlen(lastModified));

            // 将目标服务器返回的数据直接转发给客户端
            ret = send(((ProxyParam *)lpParameter)->clientSocket, cacheBuffer, sizeof(cacheBuffer), 0);
            if (ret != SOCKET_ERROR)
                printf("由缓存发送\n");
        }
    }
    // 没有cache
    else
    {
        char *p;
        char *ptr;
        const char *delim = "\r\n";
        char lastModified[100];

        // 将客户端发送的HTTP 数据报⽂直接转发给⽬标服务器
        ret = send(((ProxyParam *)lpParameter)->serverSocket, Buffer, strlen(Buffer) + 1, 0);
        // 等待⽬标服务器返回数据
        recvSize = recv(((ProxyParam *)lpParameter)->serverSocket, Buffer, MAXSIZE, 0);
        if (recvSize <= 0)
        {
            goto error;
        }

        // 复制第一行
        p = strtok_s(Buffer, delim, &ptr);
        // 数据缓存到Cache
        if (cacheNum != CACHESIZE)
        {
            p = strtok_s(NULL, delim, &ptr);
            while (p)
            {
                if (strstr(p, "Last-Modified") != NULL)
                {
                    memcpy(lastModified, &p[15], strlen(p) - 15);
                    break;
                }
                p = strtok_s(NULL, delim, &ptr);
            }
            memcpy(Cache[cachePtr].lastModified, lastModified, strlen(lastModified));
            memcpy(Cache[cachePtr].buffer, Buffer, MAXSIZE);
        }
        else
        {
            p = strtok_s(NULL, delim, &ptr);
            while (p)
            {
                if (strstr(p, "Last-Modified") != NULL)
                {
                    memcpy(lastModified, &p[15], strlen(p) - 15);
                    break;
                }
                p = strtok_s(NULL, delim, &ptr);
            }
            memcpy(Cache[cachePtr - 1].lastModified, lastModified, strlen(lastModified));
            memcpy(Cache[cachePtr - 1].buffer, Buffer, MAXSIZE);
        }

        // 将⽬标服务器返回的数据直接转发给客户端
        ret = send(((ProxyParam *)lpParameter)->clientSocket, Buffer, sizeof(Buffer), 0);
    }

// 错误处理
error:
    printf("关闭套接字\n");
    Sleep(200);
    closesocket(((ProxyParam *)lpParameter)->clientSocket);
    closesocket(((ProxyParam *)lpParameter)->serverSocket);
    delete lpParameter;
    _endthreadex(0);
    return 0;
}

//************************************
// Method: ParseHttpHead
// FullName: ParseHttpHead
// Access: public
// Returns: int cachePos
// Qualifier: 解析TCP 报⽂中的HTTP 头部
// Parameter: char * buffer
// Parameter: HttpHeader * httpHeader
//************************************
int ParseHttpHead(char *buffer, HttpHeader *httpHeader)
{
    int cachePos = -1;
    char *p;
    char *ptr;
    const char *delim = "\r\n";

    // 提取第⼀⾏
    p = strtok_s(buffer, delim, &ptr);
    printf("%s\n", p);

    if (p[0] == 'G')
    {
        // GET ⽅式
        memcpy(httpHeader->method, "GET", 3);
        memcpy(httpHeader->url, &p[4], strlen(p) - 13);

        for (int i = 0; i < cacheNum; i++)
        {
            // 查询cache
            if (strcmp(Cache[i].url, httpHeader->url) == 0)
            {
                cachePos = i;
                break;
            }
        }
        // 没有命中且有cache空位
        if ((cachePos == -1) && (cacheNum != CACHESIZE))
        {
            cachePtr = cacheNum;
            memcpy(Cache[cachePtr].url, &p[4], strlen(p) - 13);
        }
        // 没有命中但cache装满的
        else if ((cachePos == -1) && (cacheNum == CACHESIZE))
        {
            cachePtr = cachePtr % (CACHESIZE - 1);
            memcpy(Cache[cachePtr].url, &p[4], strlen(p) - 13);
        }
    }
    else if (p[0] == 'P')
    {
        // POST ⽅式
        memcpy(httpHeader->method, "POST", 4);
        memcpy(httpHeader->url, &p[5], strlen(p) - 14);

        for (int i = 0; i < CACHESIZE; i++)
        {
            // 查询cache
            if (strcmp(Cache[i].url, httpHeader->url) == 0)
            {
                cachePos = i;
                break;
            }
        }
        // 没有命中且有cache空位
        if ((cachePos == -1) && (cacheNum != CACHESIZE))
        {
            cachePtr = cacheNum;
            memcpy(Cache[cachePtr].url, &p[4], strlen(p) - 14);
        }
        // 没有命中但cache装满的
        else if ((cachePos == -1) && (cacheNum == CACHESIZE))
        {
            cachePtr = cachePtr % (CACHESIZE - 1);
            memcpy(Cache[cachePtr].url, &p[4], strlen(p) - 14);
        }
    }

    printf("%s\n", httpHeader->url);
    p = strtok_s(NULL, delim, &ptr);
    while (p)
    {
        switch (p[0])
        {
        case 'H': // Host
            memcpy(httpHeader->host, &p[6], strlen(p) - 6);
            // 没有命中且有cache空位
            if ((cachePos == -1) && (cacheNum != CACHESIZE))
            {
                memcpy(Cache[cachePtr].host, &p[6], strlen(p) - 6);
                cacheNum++;
            }
            // 没有命中但cache装满的
            else if ((cachePos == -1) && (cacheNum == CACHESIZE))
            {
                memcpy(Cache[cachePtr].host, &p[6], strlen(p) - 6);
                cachePtr++;
            }
            break;
        case 'C': // Cookie
            if (strlen(p) > 8)
            {
                char header[8];
                ZeroMemory(header, sizeof(header));
                memcpy(header, p, 6);
                if (!strcmp(header, "Cookie"))
                {
                    memcpy(httpHeader->cookie, &p[8], strlen(p) - 8);
                }
            }
            break;
        default:
            break;
        }
        p = strtok_s(NULL, delim, &ptr);
    }

    return cachePos;
}

//************************************
// Method: ParseCache
// FullName: ParseCache
// Access: public
// Returns: void
// Qualifier: 解析服务器返回的cache头部
// Parameter: char * buffer
// Parameter: char * code
// Parameter: char * lastModified
//************************************
void ParseCache(char *buffer, char *code, char *lastModified)
{
    char *p;
    char *ptr;
    const char *delim = "\r\n";

    // 提取第一行
    p = strtok_s(buffer, delim, &ptr);
    memcpy(code, &p[9], 3);
    code[3] = '\0';

    // 寻找Last-Modified
    p = strtok_s(NULL, delim, &ptr);
    while (p)
    {
        if (strstr(p, "Last-Modified") != NULL)
        {
            memcpy(lastModified, &p[15], strlen(p) - 15);
            break;
        }
        p = strtok_s(NULL, delim, &ptr);
    }
}

//************************************
// Method: ConnectToServer
// FullName: ConnectToServer
// Access: public
// Returns: BOOL
// Qualifier: 根据主机创建⽬标服务器套接字，并连接
// Parameter: SOCKET * serverSocket
// Parameter: char * host
//************************************
BOOL ConnectToServer(SOCKET *serverSocket, char *host)
{
    sockaddr_in serverAddr;
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(HTTP_PORT);
    HOSTENT *hostent = gethostbyname(host);
    if (!hostent)
    {
        return FALSE;
    }
    in_addr Inaddr = *((in_addr *)*hostent->h_addr_list);
    serverAddr.sin_addr.s_addr = inet_addr(inet_ntoa(Inaddr));
    *serverSocket = socket(AF_INET, SOCK_STREAM, 0);
    if (*serverSocket == INVALID_SOCKET)
    {
        return FALSE;
    }
    if (connect(*serverSocket, (SOCKADDR *)&serverAddr, sizeof(serverAddr)) == SOCKET_ERROR)
    {
        closesocket(*serverSocket);
        return FALSE;
    }
    return TRUE;
}
