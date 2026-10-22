package com.mofanyi.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class WorkspaceMemberVO implements Serializable {

    private Long id;
    private Long workspaceId;
    private Long userId;
    private String userAccount;
    private String userName;
    private String role;
    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
