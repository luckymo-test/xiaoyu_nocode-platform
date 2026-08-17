package com.mofanyi.manager;

import cn.hutool.core.collection.CollUtil;
import com.mofanyi.config.CosClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.PicOperations;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * COS 对象存储操作封装，只负责与腾讯云 COS 交互，不包含业务逻辑。
 *
 * @author 程序员luckymo
 */
@Component
public class CosManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    /**
     * 上传对象
     *
     * @param key  对象键（含文件夹路径）
     * @param file 文件
     * @return 上传结果
     */
    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 上传对象并附带图片处理（原图与处理结果都写入 COS）。
     * 处理规则由调用方传入（如 imageMogr2 压缩/缩略），CosManager 不关心具体业务规则。
     *
     * @param key   原图对象键（含文件夹路径）
     * @param file  原图文件
     * @param rules 图片处理规则，每个规则指定目标 fileId 与处理指令
     * @return 上传结果
     */
    public PutObjectResult putPictureObject(String key, File file, List<PicOperations.Rule> rules) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        if (CollUtil.isNotEmpty(rules)) {
            PicOperations picOperations = new PicOperations();
            // 返回原图信息，同时确保图片处理规则生效
            picOperations.setIsPicInfo(1);
            picOperations.setRules(rules);
            putObjectRequest.setPicOperations(picOperations);
        }
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 下载对象
     *
     * @param key 对象键
     * @return COS 对象
     */
    public COSObject getObject(String key) {
        GetObjectRequest getObjectRequest = new GetObjectRequest(cosClientConfig.getBucket(), key);
        return cosClient.getObject(getObjectRequest);
    }

    /**
     * 删除对象
     *
     * @param key 对象键
     */
    public void deleteObject(String key) throws CosClientException {
        cosClient.deleteObject(cosClientConfig.getBucket(), key);
    }
}
