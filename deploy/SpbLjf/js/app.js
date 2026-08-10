// js/app.js
document.addEventListener('DOMContentLoaded', function() {
  // 模拟数据
  const newsData = [
    { title: '我校在2024年全国大学生数学建模竞赛中获佳绩', date: '2025-03-15' },
    { title: '南邮科研团队在量子通信领域取得重要进展', date: '2025-03-14' },
    { title: '“邮梦”创业计划大赛圆满落幕', date: '2025-03-12' },
    { title: '校领导赴深圳华为公司洽谈合作', date: '2025-03-10' }
  ];

  const noticeData = [
    { title: '关于2025年清明节放假安排的通知', date: '2025-03-18' },
    { title: '关于开展春季校园安全检查的通知', date: '2025-03-16' },
    { title: '图书馆新增数据库开放使用通知', date: '2025-03-13' },
    { title: '2025年硕士研究生复试分数线公布', date: '2025-03-11' }
  ];

  const academicData = [
    { title: '学术讲座：人工智能与未来教育', date: '2025-03-20', desc: '主讲人：张教授' },
    { title: '学术会议：信息安全与隐私保护', date: '2025-03-22', desc: '地点：行政楼报告厅' },
    { title: '博士生论坛：数据科学前沿', date: '2025-03-25', desc: '报名截止：3月24日' }
  ];

  const campusImages = [
    'https://picsum.photos/300/200?random=10',
    'https://picsum.photos/300/200?random=11',
    'https://picsum.photos/300/200?random=12',
    'https://picsum.photos/300/200?random=13',
    'https://picsum.photos/300/200?random=14',
    'https://picsum.photos/300/200?random=15'
  ];

  // 更新新闻列表
  function renderNews() {
    const list = document.getElementById('newsList');
    if (!list) return;
    list.innerHTML = newsData.map(item => `
      <li>
        <a href="#">${item.title}</a>
        <span class="date">${item.date}</span>
      </li>
    `).join('');
  }

  // 更新通知列表
  function renderNotices() {
    const list = document.getElementById('noticeList');
    if (!list) return;
    list.innerHTML = noticeData.map(item => `
      <li>
        <a href="#">${item.title}</a>
        <span class="date">${item.date}</span>
      </li>
    `).join('');
  }

  // 学术活动卡片
  function renderAcademic() {
    const grid = document.getElementById('academicGrid');
    if (!grid) return;
    grid.innerHTML = academicData.map(item => `
      <div class="academic-card">
        <h3>${item.title}</h3>
        <p>${item.date}</p>
        <p>${item.desc}</p>
      </div>
    `).join('');
  }

  // 校园风光
  function renderCampus() {
    const gallery = document.getElementById('campusGallery');
    if (!gallery) return;
    gallery.innerHTML = campusImages.map(url => `
      <div class="campus-item">
        <img src="${url}" alt="校园风光">
      </div>
    `).join('');
  }

  // 轮播图逻辑
  const slides = document.querySelectorAll('.slide');
  const indicatorsContainer = document.getElementById('indicators');
  let currentSlide = 0;
  let autoSlideInterval;

  function createIndicators() {
    slides.forEach((_, index) => {
      const dot = document.createElement('div');
      dot.classList.add('indicator');
      if (index === 0) dot.classList.add('active');
      dot.dataset.index = index;
      dot.addEventListener('click', () => goToSlide(index));
      indicatorsContainer.appendChild(dot);
    });
  }

  function goToSlide(index) {
    slides[currentSlide].classList.remove('active');
    indicatorsContainer.children[currentSlide].classList.remove('active');
    currentSlide = index;
    slides[currentSlide].classList.add('active');
    indicatorsContainer.children[currentSlide].classList.add('active');
  }

  function nextSlide() {
    const next = (currentSlide + 1) % slides.length;
    goToSlide(next);
  }

  function prevSlide() {
    const prev = (currentSlide - 1 + slides.length) % slides.length;
    goToSlide(prev);
  }

  function startAutoSlide() {
    autoSlideInterval = setInterval(nextSlide, 5000);
  }

  function stopAutoSlide() {
    clearInterval(autoSlideInterval);
  }

  // 初始化轮播
  if (slides.length > 0) {
    createIndicators();
    document.getElementById('nextBtn').addEventListener('click', () => { nextSlide(); restartAuto(); });
    document.getElementById('prevBtn').addEventListener('click', () => { prevSlide(); restartAuto(); });
    startAutoSlide();

    // 鼠标悬停暂停
    const hero = document.querySelector('.hero-slider');
    hero.addEventListener('mouseenter', stopAutoSlide);
    hero.addEventListener('mouseleave', startAutoSlide);
  }

  function restartAuto() {
    stopAutoSlide();
    startAutoSlide();
  }

  // 移动端导航切换
  const navToggle = document.getElementById('navToggle');
  const mainNav = document.getElementById('mainNav');
  if (navToggle && mainNav) {
    navToggle.addEventListener('click', () => {
      mainNav.classList.toggle('open');
    });
  }

  // 初始化所有渲染
  renderNews();
  renderNotices();
  renderAcademic();
  renderCampus();
});