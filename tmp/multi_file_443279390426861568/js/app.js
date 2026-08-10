// ===== 使用 IIFE 避免全局污染 =====
(function() {
    'use strict';

    // 模拟天气数据源
    const weatherData = {
        '北京': { temp: 25, desc: '晴', humidity: 40, windSpeed: 12 },
        '上海': { temp: 28, desc: '多云', humidity: 60, windSpeed: 8 },
        '广州': { temp: 30, desc: '小雨', humidity: 80, windSpeed: 15 },
        '深圳': { temp: 29, desc: '雷阵雨', humidity: 85, windSpeed: 20 }
    };

    // DOM 元素
    const cityEl = document.getElementById('city-name');
    const tempEl = document.getElementById('temperature');
    const descEl = document.getElementById('description');
    const humidityEl = document.getElementById('humidity');
    const windSpeedEl = document.getElementById('wind-speed');
    const refreshBtn = document.getElementById('refresh-btn');

    // 当前城市
    let currentCity = localStorage.getItem('weatherCity') || '北京';
    cityEl.textContent = currentCity;

    // 更新天气卡片
    function updateWeather(city) {
        // 模拟异步请求
        return new Promise((resolve, reject) => {
            setTimeout(() => {
                const data = weatherData[city];
                if (data) {
                    tempEl.textContent = data.temp + '°C';
                    descEl.textContent = data.desc;
                    humidityEl.textContent = data.humidity + '%';
                    windSpeedEl.textContent = data.windSpeed + ' km/h';
                    // 保存到 localStorage
                    localStorage.setItem('weatherCity', city);
                    resolve();
                } else {
                    reject(new Error('未找到该城市的天气数据'));
                }
            }, 500);
        });
    }

    // 刷新按钮点击事件
    refreshBtn.addEventListener('click', () => {
        refreshBtn.disabled = true;
        refreshBtn.textContent = '更新中...';
        updateWeather(currentCity)
            .catch(err => {
                console.error(err);
                alert('天气数据加载失败，请稍后重试');
            })
            .finally(() => {
                refreshBtn.disabled = false;
                refreshBtn.textContent = '刷新';
            });
    });

    // 页面加载时初始化
    document.addEventListener('DOMContentLoaded', () => {
        // 先显示加载状态
        tempEl.textContent = '--°C';
        descEl.textContent = '加载中...';
        humidityEl.textContent = '--%';
        windSpeedEl.textContent = '-- km/h';
        updateWeather(currentCity);
    });
})();