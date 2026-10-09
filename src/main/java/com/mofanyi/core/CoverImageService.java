package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.config.CosClientConfig;
import com.mofanyi.config.ScreenshotProperties;
import com.mofanyi.manager.CosManager;
import com.mofanyi.mapper.AppMapper;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 应用封面图服务：对已部署应用 URL 截图，原图与压缩图上传 COS 并落库。
 * 异步执行，失败仅记日志，绝不影响部署流程。
 *
 * @author 程序员luckymo
 */
@Slf4j
@Service
public class CoverImageService {

    private final ScreenshotProperties screenshotProperties;

    private final CosManager cosManager;

    private final CosClientConfig cosClientConfig;

    private final AppMapper appMapper;

    private final ThreadPoolExecutor screenshotExecutor;

    public CoverImageService(ScreenshotProperties screenshotProperties,
                             CosManager cosManager,
                             CosClientConfig cosClientConfig,
                             AppMapper appMapper,
                             @Qualifier("screenshotThreadPoolExecutor") ThreadPoolExecutor screenshotExecutor) {
        this.screenshotProperties = screenshotProperties;
        this.cosManager = cosManager;
        this.cosClientConfig = cosClientConfig;
        this.appMapper = appMapper;
        this.screenshotExecutor = screenshotExecutor;
    }

    /**
     * 异步生成应用封面图：截图 → 上传 COS（原图 + 压缩图）→ 落库。
     * 提交到独立截图线程池后立即返回。
     *
     * @param appId     应用 id
     * @param deployUrl 已部署应用的访问地址
     */
    public void generateCoverAsync(long appId, String deployUrl) {
        screenshotExecutor.execute(() -> generateCover(appId, deployUrl));
    }

    private void generateCover(long appId, String deployUrl) {
        WebDriver driver = null;
        File screenshotFile = null;
        File compressedFile = null;
        try {
            driver = createDriver();
            driver.get(deployUrl);
            try {
                new WebDriverWait(driver, Duration.ofSeconds(10))
                        .until(d -> "complete".equals(((JavascriptExecutor) d)
                                .executeScript("return document.readyState")));
            } catch (Exception ignore) {
                // 页面加载超时不阻塞截图流程
            }
            Thread.sleep(screenshotProperties.getWaitMillis());
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            screenshotFile = new File(buildTmpDir(), "app_" + appId + ".png");
            FileUtil.writeBytes(png, screenshotFile);

            // 本地等比缩放生成压缩图（jpg），避免依赖 COS 数据万象处理
            compressedFile = scaleCompressed(screenshotFile, appId);

            String originalKey = screenshotProperties.getOriginalFolder() + "/" + appId + ".png";
            String compressedKey = screenshotProperties.getCompressedFolder() + "/" + appId + ".jpg";

            cosManager.putObject(originalKey, screenshotFile);
            cosManager.putObject(compressedKey, compressedFile);

            String coverUrl = cosClientConfig.getHost() + "/" + originalKey;
            String compressedUrl = cosClientConfig.getHost() + "/" + compressedKey;
            appMapper.updateCover(appId, coverUrl, compressedUrl);
            log.info("应用封面图生成成功，appId={}, url={}", appId, deployUrl);
        } catch (Exception e) {
            log.error("应用封面图生成失败，appId={}, url={}", appId, deployUrl, e);
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception ignore) {
                    // 忽略关闭异常
                }
            }
            if (screenshotFile != null) {
                FileUtil.del(screenshotFile);
            }
            if (compressedFile != null) {
                FileUtil.del(compressedFile);
            }
        }
    }

    /**
     * 将原图等比缩放到压缩图目标宽度，转 jpg 输出
     */
    private File scaleCompressed(File source, long appId) {
        File out = new File(buildTmpDir(), "app_" + appId + "_compressed.jpg");
        int targetW = screenshotProperties.getCompressedWidth();
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(source);
            int targetH = (int) Math.round((double) targetW * img.getHeight() / img.getWidth());
            cn.hutool.core.img.ImgUtil.scale(source, out, targetW, targetH, null);
        } catch (Exception e) {
            log.warn("压缩图生成失败，回退为原图，appId={}", appId, e);
            return source;
        }
        return out;
    }

    private WebDriver createDriver() {
        int width = screenshotProperties.getWidth();
        int height = screenshotProperties.getHeight();
        // 驱动创建逻辑统一在 BrowserFactory（与 RenderVerifier 共用）
        return BrowserFactory.create(screenshotProperties.getBrowser(),
                screenshotProperties.getDriverPath(),
                screenshotProperties.isHeadless(),
                width, height);
    }

    private File buildTmpDir() {
        String tmpDir = screenshotProperties.getTmpDir();
        return StrUtil.isBlank(tmpDir) ? FileUtil.getTmpDir() : FileUtil.mkdir(tmpDir);
    }
}
