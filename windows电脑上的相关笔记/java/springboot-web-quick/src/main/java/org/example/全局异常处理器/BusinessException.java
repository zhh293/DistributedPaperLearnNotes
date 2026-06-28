package org.example.全局异常处理器;

public class BusinessException extends RuntimeException{
    private Integer code;
    public BusinessException(Integer code,String message)
    {
        super(message);
        this.code = code;

    }
    public BusinessException(Integer code,String message,Throwable cause)
    {
        super(message,cause);
        this.code = code;
    }
    public Integer getCode()
    {
        return code;
    }
    public void setCode(Integer code)
    {
        this.code = code;
    }
}
/*Spring MVC 全局异常处理流程与实现
Spring MVC 中的全局异常处理是一种优雅处理应用程序中异常的机制，它可以避免在控制器方法中编写大量重复的异常处理代码，提高代码的可维护性和用户体验。
全局异常处理的流程
异常产生：在控制器 (Controller) 或服务层 (Service) 中发生异常
异常捕获：Spring MVC 框架捕获异常，停止当前请求的处理流程
异常传递：框架将异常传递给全局异常处理器
异常处理：全局异常处理器根据异常类型进行匹配和处理
响应返回：将处理结果 (通常是错误视图或 JSON 响应) 返回给客户端
实现全局异常处理所需的类
实现 Spring MVC 全局异常处理通常需要创建以下类：

全局异常处理器类：使用@ControllerAdvice或@RestControllerAdvice注解标记
异常处理方法：使用@ExceptionHandler注解标记，处理特定类型的异常
自定义异常类：根据业务需求创建自定义异常，通常继承自RuntimeException*/

//这里面的错误码或者message，一般都要自己写在一个类或者多个类中，方便后续的管理，降低耦合度
/*
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public Result getUser(@PathVariable Long id) {
        // 1. 业务异常示例
        if (id <= 0) {
            throw new BusinessException(40001, "用户ID无效");
        }

        // 2. 调用服务层可能抛出的异常
        return Result.success(userService.getUserById(id));
    }

    @PostMapping
    public Result createUser(@RequestBody UserCreateRequest request) {
        // 3. 参数校验异常
        if (request.getName() == null || request.getName().isEmpty()) {
            throw new BusinessException(40002, "用户名不能为空");
        }

        return Result.success(userService.createUser(request));
    }

    @DeleteMapping("/{id}")
    public Result deleteUser(@PathVariable Long id) {
        // 4. 模拟空指针异常
        if (id == 999) {
            String nullValue = null;
            nullValue.length(); // 触发NullPointerException
        }

        // 5. 模拟其他运行时异常
        if (id > 1000) {
            throw new RuntimeException("测试未知异常");
        }

        userService.deleteUser(id);
        return Result.success();
    }
}
*/
/*@Getter
public class BusinessException extends RuntimeException {
    private final Integer code;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
    */
/*
*//**
 * 全局异常处理器
 *//*
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 处理自定义业务异常
    @ExceptionHandler(BusinessException.class)
    public Result<String> handleBusinessException(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    // 处理空指针异常
    @ExceptionHandler(NullPointerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<String> handleNullPointerException(NullPointerException e) {
        return Result.fail(500, "空指针异常: " + e.getMessage());
    }

    // 处理参数校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<String> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String errorMsg = e.getBindingResult().getFieldError().getDefaultMessage();
        return Result.fail(400, "参数校验失败: " + errorMsg);
    }

    // 处理其他未捕获的异常
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<String> handleException(Exception e) {
        return Result.fail(500, "系统异常: " + e.getMessage());
    }
}
    */
/*@Data
public class Result<T> {
    private Integer code;      // 状态码
    private String message;    // 消息
    private T data;            // 数据

    // 成功响应
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("操作成功");
        result.setData(data);
        return result;
    }

    // 失败响应
    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
    */
