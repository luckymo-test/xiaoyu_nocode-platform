// ===== 页面加载完成后执行 =====
document.addEventListener('DOMContentLoaded', function() {
  console.log('页面加载完成');

  // 获取联系表单元素
  const contactForm = document.getElementById('contact-form');
  
  if (contactForm) {
    // 监听表单提交事件
    contactForm.addEventListener('submit', function(event) {
      event.preventDefault(); // 阻止默认提交

      // 获取表单数据
      const name = document.getElementById('name').value.trim();
      const email = document.getElementById('email').value.trim();
      const message = document.getElementById('message').value.trim();

      // 简单验证
      if (!name || !email || !message) {
        alert('请填写所有必填字段');
        return;
      }

      if (!isValidEmail(email)) {
        alert('请输入有效的邮箱地址');
        return;
      }

      // 保存到 localStorage（模拟提交）
      const submission = {
        name: name,
        email: email,
        message: message,
        timestamp: new Date().toISOString()
      };

      // 获取已有数据，没有则创建数组
      let submissions = [];
      const stored = localStorage.getItem('contactSubmissions');
      if (stored) {
        try {
          submissions = JSON.parse(stored);
        } catch (e) {
          console.error('解析存储数据失败', e);
        }
      }

      submissions.push(submission);
      localStorage.setItem('contactSubmissions', JSON.stringify(submissions));

      // 清空表单
      contactForm.reset();
      alert('提交成功！感谢您的留言。');
    });
  }

  // 邮箱格式验证函数
  function isValidEmail(email) {
    const regex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return regex.test(email);
  }
});
