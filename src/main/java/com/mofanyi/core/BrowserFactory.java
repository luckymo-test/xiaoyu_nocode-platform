package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Headless 浏览器工厂：封面截图（CoverImageService）与渲染验证（RenderVerifier）共用一套驱动创建逻辑。
 * 默认 Edge（本机已装），可选 Chrome；driver 路径为空时交给 Selenium Manager。
 */
public final class BrowserFactory {

    private BrowserFactory() {
    }

    public static WebDriver create(String browser, String driverPath, boolean headless, int width, int height) {
        return create(browser, driverPath, headless, width, height, false);
    }

    /**
     * @param enableBrowserLog 是否开启浏览器控制台日志采集（仅渲染验证需要；部分驱动不支持会抛异常，调用方自行降级）
     */
    public static WebDriver create(String browser, String driverPath, boolean headless,
                                   int width, int height, boolean enableBrowserLog) {
        String size = "--window-size=" + width + "," + height;
        if ("chrome".equalsIgnoreCase(browser)) {
            if (StrUtil.isNotBlank(driverPath)) {
                System.setProperty("webdriver.chrome.driver", driverPath);
            }
            ChromeOptions options = new ChromeOptions();
            if (headless) {
                options.addArguments("--headless=new");
            }
            options.addArguments("--disable-gpu", "--no-sandbox", size);
            if (enableBrowserLog) {
                options.setCapability("goog:loggingPrefs", loggingPrefs());
            }
            return new ChromeDriver(options);
        }
        if (StrUtil.isNotBlank(driverPath)) {
            System.setProperty("webdriver.edge.driver", driverPath);
        }
        EdgeOptions options = new EdgeOptions();
        if (headless) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--disable-gpu", "--no-sandbox", size);
        if (enableBrowserLog) {
            options.setCapability("goog:loggingPrefs", loggingPrefs());
        }
        return new EdgeDriver(options);
    }

    private static Map<String, Object> loggingPrefs() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("browser", "ALL");
        return prefs;
    }
}
