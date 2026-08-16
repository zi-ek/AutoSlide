'use strict';

/**
 * 用户协议与隐私政策路由。
 *
 * 应用首启的「使用声明」弹窗里的两个链接指向这里，
 * 地址在 app 的 Constants.kt（URL_TERMS / URL_PRIVACY）中拼接。
 */

const { sendHtml } = require('./http');
const { termsHtml, privacyHtml } = require('./views/legal');

function register(router) {
  router.on('GET', '/terms', (req, res) => sendHtml(res, termsHtml()));
  router.on('GET', '/privacy', (req, res) => sendHtml(res, privacyHtml()));
}

module.exports = { register };
