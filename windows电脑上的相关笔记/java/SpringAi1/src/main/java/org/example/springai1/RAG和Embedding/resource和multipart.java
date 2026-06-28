package org.example.springai1.RAG和Embedding;

public class resource和multipart {






   /* 要明确 Resource 和 MultipartFile 的选择逻辑，核心是抓住两者的 本质定位差异：
    Resource 是 Spring 通用资源抽象，面向所有场景的 “资源访问”；
    MultipartFile 是 Spring Web 专用文件上传接口，仅面向 “HTTP 文件上传” 场景。
    选择的核心依据是 使用环境（Web / 非 Web）和 业务需求（接收上传 / 访问资源 / 处理资源），以下分场景详细说明。
    一、先明确两者的本质区别
    在选择前，必须先理清两者的核心定位，避免混淆：
    维度	Resource（Spring 核心）	MultipartFile（Spring Web）
    适用环境	全场景（Web 环境、非 Web 环境如定时任务、本地服务）	仅 Web 环境（处理 HTTP 协议的文件上传）
    资源来源	本地文件、类路径资源（classpath:）、网络资源（http:）、Jar 包内资源等	仅来自客户端 HTTP 上传（如表单提交、接口上传）
    核心目标	统一 资源访问方式（无论资源在哪，都用同一接口操作）	专门处理 上传文件的属性与保存（带表单上下文）
    关键属性 / 方法	getInputStream()（读资源）、getFile()（转本地文件）、getURI()（资源路径）	getOriginalFilename()（上传文件名）、transferTo()（保存到磁盘）、getContentType()（文件 MIME 类型）
    依赖模块	spring-core（核心模块，所有 Spring 应用都依赖）	spring-web（Web 模块，仅 Web 应用需依赖）
    二、分场景选择：核心决策逻辑
1. 非 Web 环境（无 HTTP 上传）：必选 Resource
    如果你的应用是 非 Web 服务（如本地定时任务、批处理程序、控制台应用），或业务不涉及 “接收用户上传文件”，则 只能用 Resource，因为 MultipartFile 依赖 Web 环境和 HTTP 协议，无法在非 Web 环境使用。
    典型场景：
    定时任务读取本地磁盘的日志文件进行分析；
    服务启动时加载类路径下的配置文件（classpath:application.yml）；
    读取远程网络资源（如 http://example.com/data.txt）并解析；
    读取 Jar 包内的静态资源（如 META-INF/templates/report.docx）。
    示例代码（读取类路径下的配置文件）：
    java
            运行
    // 非 Web 环境，用 ClassPathResource 访问类路径资源
    Resource configResource = new ClassPathResource("config/app.properties");
try (InputStream in = configResource.getInputStream()) {
        // 读取配置内容
        Properties props = new Properties();
        props.load(in);
    } catch (IOException e) {
        e.printStackTrace();
    }
2. Web 环境：根据 “业务角色” 选择
    Web 环境下（如 Spring Boot Web 应用），两者并非互斥，而是常 协同使用：MultipartFile 负责 “接收上传”，Resource 负责 “后续资源处理”。
    场景 2.1：接收客户端上传的文件 → 必选 MultipartFile
    当接口需要 接收用户通过 HTTP 上传的文件（如前端表单提交、Postman 上传文件）时，只能用 MultipartFile，因为它是 Spring 定义的 “文件上传标准接口”，自动封装了上传文件的文件名、MIME 类型、表单字段名等关键信息，且提供安全的文件保存方法（transferTo()）。
    典型场景：
    用户上传头像、简历（.pdf/.docx）；
    管理员上传 Excel 数据文件进行批量导入；
    前端上传图片到服务端存储。
    示例代码（Web 接口接收上传文件）：
    java
            运行
    @RestController
    @RequestMapping("/upload")
    public class UploadController {

        // 接收上传文件，参数类型必须是 MultipartFile
        @PostMapping("/file")
        public String uploadFile(@RequestParam("file") MultipartFile file) {
            if (file.isEmpty()) {
                return "请选择上传文件";
            }

            try {
                // 保存上传文件到本地磁盘（用 MultipartFile 专用方法 transferTo()）
                File dest = new File("uploads/" + file.getOriginalFilename());
                file.transferTo(dest); // 自动处理流关闭，比手动读流更安全
                return "文件上传成功：" + dest.getAbsolutePath();
            } catch (IOException e) {
                e.printStackTrace();
                return "文件上传失败";
            }
        }
    }
    场景 2.2：处理已上传 / 已存在的资源 → 优先 Resource
    当文件已上传到服务端（如保存到磁盘、存储在 OSS），或需要访问服务端本地 / 类路径 / 网络资源时，优先用 Resource，因为它能统一处理所有类型的资源，且与 Spring 生态（如 Spring AI 文件读取器、资源解析器）更好兼容。
    典型场景：
    解析用户上传的 PDF 文件内容（先通过 MultipartFile 接收，转成 Resource 后用 PdfReader 解析）；
    读取服务端本地的模板文件（如 templates/contract.docx），填充数据后返回给用户下载；
    从 OSS 下载文件（自定义 Resource 实现，如 OssResource）并处理。
    示例代码（接收上传后转 Resource 解析）：
    java
            运行
    @RestController
    @RequestMapping("/upload")
    public class UploadController {

        @PostMapping("/pdf-parse")
        public String parsePdf(@RequestParam("file") MultipartFile multipartFile) throws IOException {
            // 1. 接收上传文件（MultipartFile）
            if (multipartFile.isEmpty()) {
                return "请选择 PDF 文件";
            }

            // 2. 转成 Resource（方便用 Spring AI 的 PdfReader 解析）
            Resource pdfResource = new InputStreamResource(multipartFile.getInputStream()) {
                @Override
                public String getFilename() {
                    return multipartFile.getOriginalFilename(); // 必须重写，否则文件名null
                }
            };

            // 3. 用 Spring AI 的 PdfReader 解析 PDF 内容（依赖 Resource 接口）
            PdfReader pdfReader = new PdfReader(pdfResource);
            List<Document> documents = pdfReader.read();

            // 4. 拼接解析结果
            StringBuilder content = new StringBuilder();
            for (Document doc : documents) {
                content.append("页码 ").append(doc.getMetadata().get("page")).append(": ").append(doc.getContent()).append("\n");
            }
            return "PDF 解析结果：\n" + content;
        }
    }
    场景 2.3：服务间传递文件 → 按需转换
    如果需要将文件从 A 服务传递到 B 服务（如 A 接收上传，B 负责解析），则需根据传递方式选择：
    若通过 HTTP 多部分请求（multipart/form-data）传递，B 服务接收时需用 MultipartFile；
    若 A 服务先将文件保存到共享存储（如 OSS），B 服务从 OSS 读取，则 B 服务用 Resource（如自定义 OssResource）访问。
    示例（A 服务将 Resource 转 MultipartFile 传给 B 服务）：
    java
            运行
    // A 服务：将本地 Resource 转成 MultipartFile，用 RestTemplate 传给 B 服务
    @Service
    public class FileTransferService {

        private final RestTemplate restTemplate;

        public FileTransferService(RestTemplate restTemplate) {
            this.restTemplate = restTemplate;
        }

        public void sendFileToServiceB() throws IOException {
            // 1. 读取本地资源（如服务端生成的报表）
            Resource reportResource = new FileSystemResource("reports/monthly.pdf");

            // 2. 转成 MultipartFile（用于 HTTP 多部分请求）
            MultipartFile multipartFile = new MockMultipartFile(
                    "file", // 表单字段名，需与 B 服务接口一致
                    reportResource.getFilename(),
                    MediaType.APPLICATION_PDF_VALUE,
                    reportResource.getInputStream()
            );

            // 3. 用 RestTemplate 发送多部分请求到 B 服务
            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(createMultipartBody(multipartFile), createHeaders());
            restTemplate.postForObject("http://service-b/api/receive-file", request, String.class);
        }

        // 构建多部分请求体
        private MultiValueMap<String, Object> createMultipartBody(MultipartFile file) {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", file);
            return body;
        }

        // 设置请求头
        private HttpHeaders createHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            return headers;
        }
    }
    三、协同使用的典型流程（Web 环境）
    在实际 Web 开发中，MultipartFile 和 Resource 常配合使用，流程如下：
    接收上传：前端通过 multipart/form-data 提交文件，后端用 MultipartFile 接收；
    转存 / 转换：将 MultipartFile 保存到磁盘（transferTo()）或转成 Resource（如 InputStreamResource/FileSystemResource）；
    资源处理：用 Resource 对接 Spring 生态工具（如 Spring AI 的 PdfReader/TextFileReader 解析内容、ResourceLoader 加载其他资源）；
    后续操作：解析后的内容用于 AI 问答、数据入库，或生成新资源返回给用户下载（如转成 Resource 后通过 ResponseEntity 下载）。
    四、总结：选择优先级
*/









}
