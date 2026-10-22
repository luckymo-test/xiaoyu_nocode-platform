package com.mofanyi.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppRevisionDiffVO implements Serializable {

    private Long fromRevisionId;
    private Long toRevisionId;
    private List<FileChange> files = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileChange implements Serializable {
        private String path;
        private String changeType;
        private String fromSha256;
        private String toSha256;

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}
