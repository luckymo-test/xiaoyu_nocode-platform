// ===== 模块化 JavaScript =====

// 常见问题数据
const faqs = [
    { question: '如何重置密码？', answer: '在登录页面点击“忘记密码”，按照提示操作即可。' },
    { question: '如何联系人工客服？', answer: '您可以通过在线咨询或拨打客服热线400-000-0000。' },
    { question: '支持哪些支付方式？', answer: '我们支持微信、支付宝和银行卡支付。' },
    { question: '如何申请退款？', answer: '在订单详情页点击“申请退款”，填写原因后提交。' }
];

// 智能回复逻辑（基于关键词）
function getBotReply(userMessage) {
    const msg = userMessage.toLowerCase();
    if (msg.includes('密码') || msg.includes('登录')) {
        return '如果您忘记密码，可以在登录页面点击“忘记密码”进行重置。';
    } else if (msg.includes('人工')) {
        return '您可以通过在线咨询或拨打客服热线400-000-0000联系人工客服。';
    } else if (msg.includes('支付')) {
        return '我们支持微信、支付宝和银行卡支付，方便快捷。';
    } else if (msg.includes('退款')) {
        return '在订单详情页点击“申请退款”，填写原因后提交，我们将在1-3个工作日内处理。';
    } else {
        return '感谢您的咨询，我们的客服人员会尽快联系您，请保持手机畅通。';
    }
}

// 渲染常见问题列表
function renderFaqs() {
    const faqList = document.getElementById('faqList');
    faqList.innerHTML = '';
    faqs.forEach((faq, index) => {
        const li = document.createElement('li');
        li.className = 'faq__item';
        li.innerHTML = `
            <div class="faq__question">${faq.question}</div>
            <div class="faq__answer">${faq.answer}</div>
        `;
        li.addEventListener('click', () => {
            li.classList.toggle('faq__item--active');
        });
        faqList.appendChild(li);
    });
}

// 添加消息到聊天区
function addMessage(content, type) {
    const chatMessages = document.getElementById('chatMessages');
    const messageDiv = document.createElement('div');
    messageDiv.className = `chat__message chat__message--${type}`;
    messageDiv.textContent = content;
    chatMessages.appendChild(messageDiv);
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

// 保存聊天记录到localStorage
function saveChatHistory() {
    const chatMessages = document.getElementById('chatMessages');
    const messages = chatMessages.querySelectorAll('.chat__message');
    const history = Array.from(messages).map(msg => ({
        content: msg.textContent,
        type: msg.classList.contains('chat__message--user') ? 'user' : 'bot'
    }));
    localStorage.setItem('chatHistory', JSON.stringify(history));
}

// 加载聊天记录
function loadChatHistory() {
    const history = JSON.parse(localStorage.getItem('chatHistory')) || [];
    const chatMessages = document.getElementById('chatMessages');
    history.forEach(item => {
        addMessage(item.content, item.type);
    });
}

// 处理表单提交
function handleSubmit(event) {
    event.preventDefault();
    const userInput = document.getElementById('userInput');
    const userMessage = userInput.value.trim();
    if (!userMessage) return;

    addMessage(userMessage, 'user');
    userInput.value = '';

    // 模拟智能回复（延迟1秒）
    setTimeout(() => {
        const botReply = getBotReply(userMessage);
        addMessage(botReply, 'bot');
        saveChatHistory();
    }, 1000);

    saveChatHistory();
}

// 初始化
function init() {
    renderFaqs();
    loadChatHistory();
    document.getElementById('chatForm').addEventListener('submit', handleSubmit);
}

// 在DOM加载完成后执行
document.addEventListener('DOMContentLoaded', init);