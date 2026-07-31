package org.apache.seatunnel.web.api.controller;

import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.aspect.AccessLogAnnotation;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.UsersService;
import org.apache.seatunnel.web.common.constants.Constants;
import org.apache.seatunnel.web.common.enums.UserType;
import org.apache.seatunnel.web.dao.entity.User;
import org.apache.seatunnel.web.spi.bean.dto.UserDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;


/**
 * users controller
 */
@RestController
@RequestMapping("/api/v1/users")
public class UsersController extends BaseController {

    @Resource
    private UsersService usersService;

    @Resource
    private CurrentUserProvider currentUserProvider;

    /**
     * get user info
     *
     * @param loginUser login user
     * @return user info
     */
    @GetMapping(value = "/get-user-info")
    @ResponseStatus(HttpStatus.OK)
    @AccessLogAnnotation
    public Result<User> getUserInfo(@RequestAttribute(value = Constants.SESSION_USER) User loginUser) {
        return Result.buildSuc(usersService.getUserInfo(loginUser));

    }

    @GetMapping("/currentUser")
    public Result<UserDTO> currentUser() {

        User loginUser = currentUserProvider.getCurrentUser();

        if (loginUser == null) {
            return Result.buildFailure("NOT_LOGIN");
        }

        UserDTO dto = new UserDTO();
        dto.setId(loginUser.getId());
        dto.setName(loginUser.getUserName());
        dto.setUserid(String.valueOf(loginUser.getId()));
        dto.setAccess(loginUser.getUserType() == null || loginUser.getUserType() == UserType.ADMIN_USER
                ? "admin"
                : "user");
        dto.setUserName(loginUser.getUserName());
        dto.setEmail(loginUser.getEmail());
        dto.setPhone(loginUser.getPhone());
        dto.setUserType(loginUser.getUserType());
        dto.setState(loginUser.getState());
        dto.setCreateTime(loginUser.getCreateTime());
        dto.setUpdateTime(loginUser.getUpdateTime());
        return Result.buildSuc(dto);
    }
}
