// ===== 页面交互逻辑 =====
document.addEventListener('DOMContentLoaded', function() {

  // 搜索功能
  const searchBtn = document.getElementById('searchBtn');
  const searchInput = document.getElementById('searchInput');

  if (searchBtn && searchInput) {
    searchBtn.addEventListener('click', function() {
      const query = searchInput.value.trim();
      if (query) {
        alert('您搜索的是：' + query + '\n（此处可跳转到搜索结果页）');
      } else {
        alert('请输入搜索关键词');
      }
    });

    // 回车触发搜索
    searchInput.addEventListener('keydown', function(e) {
      if (e.key === 'Enter') {
        searchBtn.click();
      }
    });
  }

  // 导航栏点击高亮
  const navLinks = document.querySelectorAll('.nav ul li a');
  navLinks.forEach(function(link) {
    link.addEventListener('click', function(e) {
      // 阻止默认跳转，仅作演示
      e.preventDefault();
      navLinks.forEach(function(item) {
        item.classList.remove('active');
      });
      link.classList.add('active');
    });
  });

  // 新闻卡片点击提示（演示）
  const newsCards = document.querySelectorAll('.news-card');
  newsCards.forEach(function(card) {
    card.addEventListener('click', function() {
      const title = card.querySelector('h3').textContent;
      alert('您点击了新闻：' + title);
    });
  });

});
