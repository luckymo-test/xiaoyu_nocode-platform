// app.js - 电商首页交互逻辑

/**
 * 商品数据（模拟）
 * 实际项目中可从后端 API 获取
 */
const products = [
  { id: 1, name: '无线蓝牙耳机', price: 299, originalPrice: 599, image: 'https://via.placeholder.com/300x200/ff4d4f/fff?text=耳机', category: '数码' },
  { id: 2, name: '智能手表', price: 899, originalPrice: 1299, image: 'https://via.placeholder.com/300x200/ff4d4f/fff?text=手表', category: '数码' },
  { id: 3, name: '便携咖啡机', price: 499, originalPrice: 799, image: 'https://via.placeholder.com/300x200/ff4d4f/fff?text=咖啡机', category: '家电' },
  { id: 4, name: '运动跑鞋', price: 399, originalPrice: 699, image: 'https://via.placeholder.com/300x200/ff4d4f/fff?text=跑鞋', category: '运动' },
  { id: 5, name: '双肩背包', price: 199, originalPrice: 399, image: 'https://via.placeholder.com/300x200/ff4d4f/fff?text=背包', category: '箱包' },
  { id: 6, name: '机械键盘', price: 599, originalPrice: 999, image: 'https://via.placeholder.com/300x200/ff4d4f/fff?text=键盘', category: '数码' }
];

/**
 * 购物车数据，使用 localStorage 持久化
 */
let cart = JSON.parse(localStorage.getItem('cart')) || [];

/**
 * DOM 元素引用
 */
const productGrid = document.getElementById('productGrid');
const cartCount = document.getElementById('cartCount');
const searchInput = document.querySelector('.header__search-input');
const searchBtn = document.querySelector('.header__search-btn');

/**
 * 更新购物车数量显示
 */
function updateCartCount() {
  const total = cart.reduce((sum, item) => sum + item.quantity, 0);
  cartCount.textContent = total;
  // 持久化
  localStorage.setItem('cart', JSON.stringify(cart));
}

/**
 * 添加商品到购物车
 * @param {number} productId - 商品 ID
 */
function addToCart(productId) {
  const existing = cart.find(item => item.id === productId);
  if (existing) {
    existing.quantity += 1;
  } else {
    cart.push({ id: productId, quantity: 1 });
  }
  updateCartCount();
  alert('已加入购物车！');
}

/**
 * 渲染商品列表
 * @param {Array} productList - 要渲染的商品数组
 */
function renderProducts(productList) {
  productGrid.innerHTML = '';
  if (productList.length === 0) {
    productGrid.innerHTML = '<p class="product-empty">没有找到相关商品</p>';
    return;
  }
  productList.forEach(product => {
    const card = document.createElement('div');
    card.className = 'product-card';
    card.dataset.id = product.id;
    card.innerHTML = `
      <img src="${product.image}" alt="${product.name}" class="product-card__image">
      <div class="product-card__info">
        <h3 class="product-card__name">${product.name}</h3>
        <p class="product-card__price">¥${product.price}
          <span class="product-card__original-price">¥${product.originalPrice}</span>
        </p>
      </div>
      <button class="product-card__btn" data-id="${product.id}">加入购物车</button>
    `;
    productGrid.appendChild(card);
  });
}

/**
 * 搜索过滤商品
 * @param {string} keyword - 搜索关键词
 */
function filterProducts(keyword) {
  const lowerKeyword = keyword.toLowerCase().trim();
  if (!lowerKeyword) {
    renderProducts(products);
    return;
  }
  const filtered = products.filter(product => 
    product.name.toLowerCase().includes(lowerKeyword) || 
    product.category.toLowerCase().includes(lowerKeyword)
  );
  renderProducts(filtered);
}

/**
 * 事件委托：处理加入购物车按钮点击
 */
productGrid.addEventListener('click', (e) => {
  const btn = e.target.closest('.product-card__btn');
  if (btn) {
    const productId = parseInt(btn.dataset.id);
    addToCart(productId);
  }
});

/**
 * 搜索事件绑定
 */
searchBtn.addEventListener('click', () => {
  filterProducts(searchInput.value);
});

searchInput.addEventListener('keyup', (e) => {
  if (e.key === 'Enter') {
    filterProducts(searchInput.value);
  }
});

/**
 * 初始化页面
 */
document.addEventListener('DOMContentLoaded', () => {
  renderProducts(products);
  updateCartCount();
});