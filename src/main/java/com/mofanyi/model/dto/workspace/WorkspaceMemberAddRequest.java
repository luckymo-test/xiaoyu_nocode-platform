package com.mofanyi.model.dto.workspace;

import lombok.Data;

import java.io.Serializable;

@Data
public class WorkspaceMemberAddRequest implements Serializable {

    private String userAccount;
    private String role;

    private static final long serialVersionUID = 1L;
}
