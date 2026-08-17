package com.mofanyi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用封面截图配置。
 * 对应 application.yml 的 screenshot 前缀。
 */
@Data
@Component
@ConfigurationProperties(prefix = "screenshot")
public class ScreenshotProperties {

    /**
     * 截图浏览器：edge / chrome
     */
    private String browser = "edge";

    /**
     * 本地浏览器 driver 绝对路径（如 msedgedriver.exe / chromedriver.exe）。
     * 配置后优先使用，绕过 Selenium Manager 联网下载（离线环境必备）。
     */
    private String driverPath;

    /**
     * 是否无头模式
     */
    private boolean headless = true;

    /**
     * 截图视口宽
     */
    private int width = 1440;

    /**
     * 截图视口高
     */
    private int height = 900;

    /**
     * 页面加载完成后的渲染等待时长（毫秒），Vue SPA 需要时间渲染
     */
    private int waitMillis = 5000;

    /**
     * 截图临时目录（默认使用系统临时目录）
     */
    private String tmpDir;

    /**
     * COS 原图文件夹
     */
    private String originalFolder = "app-cover/original";

    /**
     * COS 压缩图文件夹
     */
    private String compressedFolder = "app-cover/compressed";

    /**
     * 压缩图目标宽度（COS 端等比缩放）
     */
    private int compressedWidth = 480;
}
