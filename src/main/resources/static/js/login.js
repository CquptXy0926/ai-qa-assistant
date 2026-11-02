async function api(path, opts) {
  const resp = await fetch(path, Object.assign({ credentials: 'same-origin' }, opts || {}));
  if (resp.status === 401 && !path.includes('/auth/')) {
    location.href = 'login.html';
    throw new Error('未登录');
  }
  return resp;
}

document.addEventListener('DOMContentLoaded', () => {
  const $ = id => document.getElementById(id);
  const showMsg = t => { $('msg').textContent = t; };

  async function doLogin() {
    const username = $('username').value.trim();
    const password = $('password').value;
    if (!username || !password) return showMsg('请输入用户名和密码');
    showMsg('');
    const resp = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ username, password })
    });
    const data = await resp.json().catch(() => ({}));
    if (resp.ok) {
      location.href = 'index.html';
    } else {
      showMsg(data.error || '登录失败');
    }
  }

  $('btnLogin').onclick = doLogin;
  $('password').addEventListener('keydown', e => { if (e.key === 'Enter') doLogin(); });
  $('username').addEventListener('keydown', e => { if (e.key === 'Enter') $('password').focus(); });
});
