package plus.maa.backend.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import plus.maa.backend.common.annotation.CurrentUser;
import plus.maa.backend.config.external.MaaCopilotProperties;
import plus.maa.backend.controller.request.*;
import plus.maa.backend.controller.response.MaaLoginRsp;
import plus.maa.backend.controller.response.MaaResult;
import plus.maa.backend.controller.response.MaaUserInfo;
import plus.maa.backend.service.EmailService;
import plus.maa.backend.service.UserService;
import plus.maa.backend.service.model.LoginUser;

import java.io.IOException;

/**
 * 用户相关接口
 * <a href=
 * "https://github.com/MaaAssistantArknights/maa-copilot-frontend/blob/dev/src/apis/auth.ts">前端api约定文件</a>
 *
 * @author AnselYuki
 */
@Data
@Tag(name = "CopilotUser")
@RequestMapping("/user")
@Validated
@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final EmailService emailService;
    private final MaaCopilotProperties properties;
    @Value("${maa-copilot.jwt.header}")
    private String header;

    /**
     * 激活token中的用户
     *
     * @param activateDTO 激活码
     * @return 成功响应
     */
    @PostMapping("/activate")
    public MaaResult<Void> activate(@CurrentUser LoginUser user,
                                    @Valid @RequestBody ActivateDTO activateDTO) {
        userService.activateUser(user, activateDTO);
        return MaaResult.success();
    }

    /**
     * 注册完成后发送邮箱激活码
     *
     * @return null
     */
    @PostMapping("/activate/request")
    public MaaResult<Void> activateRequest(@CurrentUser LoginUser user) {
        userService.sendEmailCode(user);
        return MaaResult.success();
    }

    /**
     * 更新当前用户的密码(根据原密码)
     *
     * @return http响应
     */
    @PostMapping("/update/password")
    public MaaResult<Void> updatePassword(@CurrentUser LoginUser user,
                                          @RequestBody @Valid PasswordUpdateDTO updateDTO) {
        userService.modifyPassword(user, updateDTO.getNewPassword());
        return MaaResult.success();
    }

    /**
     * 更新用户详细信息
     *
     * @param updateDTO 用户信息参数
     * @return http响应
     */
    @PostMapping("/update/info")
    public MaaResult<Void> updateInfo(@CurrentUser LoginUser user,
                                      @Valid @RequestBody UserInfoUpdateDTO updateDTO) {
        userService.updateUserInfo(user, updateDTO);
        return MaaResult.success();
    }

    // TODO 邮件重置密码需要在用户未登录的情况下使用，需要修改

    /**
     * 邮箱重设密码
     *
     * @param passwordResetDTO 通过邮箱修改密码请求
     * @return 成功响应
     */
    @PostMapping("/password/reset")
    public MaaResult<Void> passwordReset(@RequestBody @Valid PasswordResetDTO passwordResetDTO) {
        // 校验用户邮箱是否存在
        userService.checkUserExistByEmail(passwordResetDTO.getEmail());
        userService.modifyPasswordByActiveCode(passwordResetDTO);
        return MaaResult.success();
    }

    /**
     * 验证码重置密码功能：
     * 发送验证码用于重置
     *
     * @return 成功响应
     */
    @PostMapping("/password/reset_request")
    public MaaResult<Void> passwordResetRequest(@RequestBody @Valid PasswordResetVCodeDTO passwordResetVCodeDTO) {
        // 校验用户邮箱是否存在
        userService.checkUserExistByEmail(passwordResetVCodeDTO.getEmail());
        emailService.sendVCode(passwordResetVCodeDTO.getEmail());
        return MaaResult.success();
    }

    /**
     * 刷新token
     *
     * @param request http请求，用于获取请求头
     * @return 成功响应
     */
    @PostMapping("/refresh")
    public MaaResult<Void> refresh(HttpServletRequest request) {
        String token = request.getHeader(header);
        userService.refreshToken(token);
        return MaaResult.success();
    }

    /**
     * 用户注册
     *
     * @param user 传入用户参数
     * @return 注册成功用户信息摘要
     */
    @PostMapping("/register")
    public MaaResult<MaaUserInfo> register(@Valid @RequestBody RegisterDTO user) {
        return MaaResult.success(userService.register(user));
    }

    /**
     * 获得注册时的验证码
     */
    @PostMapping("/sendRegistrationToken")
    public MaaResult<Void> sendRegistrationToken(@RequestBody SendRegistrationTokenDTO regDTO) {
        //FIXME: 增加频率限制或者 captcha
        emailService.sendVCode(regDTO.getEmail());
        return new MaaResult<>(204, null, null);
    }

    /**
     * 用户登录
     *
     * @param user 登录参数
     * @return 成功响应，荷载JwtToken
     */
    @PostMapping("/login")
    public MaaResult<MaaLoginRsp> login(@RequestBody @Valid LoginDTO user) {
        return MaaResult.success("登陆成功", userService.login(user));
    }

    @GetMapping("/activateAccount")
    public MaaResult<Void> activateAccount(EmailActivateReq activateDTO, HttpServletResponse response) {
        userService.activateAccount(activateDTO);
        // 激活成功 跳转页面
        try {
            response.sendRedirect(properties.getInfo().getFrontendDomain());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return MaaResult.success();
    }
}
