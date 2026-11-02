/* ================= 通用 ================= */
const $ = id => document.getElementById(id);
let ME = { username: '', role: 'USER' };
let kbs = [];
let curConv = null;
let pollingTimer = null;

async function api(path, opts) {
  const resp = await fetch(path, Object.assign({ credentials: 'same-origin' }, opts || {}));
  if (resp.status === 401) { location.href = 'login.html'; throw new Error('未登录'); }
  return resp;
}
async function apiJson(path, opts) {
  const resp = await api(path, opts);
  if (resp.status === 204) return null;
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) throw new Error(data.error || ('请求失败 ' + resp.status));
  return data;
}
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

/* ================= 启动 ================= */
document.addEventListener('DOMContentLoaded', async () => {
  ME = await apiJson('/api/auth/me');
  if (!ME.logged) { location.href = 'login.html'; return; }
  $('who').textContent = ME.username + (ME.role === 'ADMIN' ? '（管理员）' : '');
  if (ME.role !== 'ADMIN') {
    document.querySelectorAll('.admin-only').forEach(el => el.classList.add('hidden'));
  }
  bindNav();
  bindChat();
  bindAdmin();
  bindUsers();
  bindPwdModal();
  await loadKbs();
  loadKbChecks();   // 【修复】初始化时立即渲染提问范围,避免普通用户无历史会话时永远显示"加载中…"
  await loadConvs();
});

function bindNav() {
  document.querySelectorAll('.tab').forEach(tab => {
    tab.onclick = () => {
      document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      document.querySelectorAll('.view').forEach(v => v.classList.add('hidden'));
      const view = tab.dataset.view;
      $('view-' + view).classList.remove('hidden');
      $('view-' + view).classList.toggle('flex', view === 'chat');
      if (view === 'admin') { renderAdmin(); }
      if (view === 'dash') { loadDash(); }
      if (view === 'users') { loadUsers(); }
      if (view === 'chat') { loadKbChecks(); }
    };
  });
  $('btnLogout').onclick = async () => { await api('/api/auth/logout', { method: 'POST' }); location.href = 'login.html'; };
}

/* ================= 问答 ================= */
async function loadKbs() {
  kbs = await apiJson('/api/kb');
}

function loadKbChecks() {
  const box = $('kbChecks');
  box.innerHTML = '';
  if (!kbs.length) {
    box.innerHTML = '<span class="muted">暂无知识库，请管理员先创建并上传文档</span>';
    return;
  }
  const sel = curConv && curConv.kbIds ? curConv.kbIds.split(',') : null;
  kbs.forEach(kb => {
    const lab = document.createElement('label');
    const checked = !sel || sel.includes(String(kb.id));
    lab.innerHTML = `<input type="checkbox" value="${kb.id}" ${checked ? 'checked' : ''}> ${esc(kb.name)}`;
    box.appendChild(lab);
  });
}

function selectedKbIds() {
  return Array.from(document.querySelectorAll('#kbChecks input:checked')).map(i => Number(i.value));
}

async function loadConvs() {
  const list = await apiJson('/api/conversations');
  const ul = $('convList');
  ul.innerHTML = '';
  list.forEach(c => {
    const li = document.createElement('li');
    li.dataset.id = c.id;
    li.innerHTML = `<span class="t">${esc(c.title)}</span><button class="del" title="删除">✕</button>`;
    li.querySelector('.t').onclick = () => openConv(c);
    li.querySelector('.del').onclick = async (e) => {
      e.stopPropagation();
      if (!confirm('删除该会话及其全部消息？')) return;
      await api('/api/conversations/' + c.id, { method: 'DELETE' });
      if (curConv && curConv.id === c.id) { curConv = null; $('messages').innerHTML = '<div class="empty-tip">选择或新建会话开始提问</div>'; }
      loadConvs();
    };
    ul.appendChild(li);
  });
  if (!curConv && list.length) openConv(list[0]);
}

async function openConv(c) {
  curConv = c;
  document.querySelectorAll('.conv-list li').forEach(li => li.classList.toggle('active', li.dataset.id == c.id));
  loadKbChecks();
  const msgs = await apiJson('/api/conversations/' + c.id + '/messages');
  const box = $('messages');
  box.innerHTML = '';
  if (!msgs.length) box.innerHTML = '<div class="empty-tip">开始提问吧</div>';
  msgs.forEach(m => {
    const cites = typeof m.citations === 'string' ? JSON.parse(m.citations) : (m.citations || []);
    appendMsg(m.role === 'user' ? 'user' : 'ai', m.content, cites, m.id, m.rating);
  });
  box.scrollTop = box.scrollHeight;
}

function bindChat() {
  $('btnNewConv').onclick = async () => {
    const kbIds = selectedKbIds();
    const c = await apiJson('/api/conversations', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ kbIds })
    });
    curConv = { id: c.id, title: c.title, kbIds: kbIds.join(',') };
    await loadConvs();
    $('messages').innerHTML = '<div class="empty-tip">新会话，开始提问</div>';
    $('input').focus();
  };
  $('btnSend').onclick = send;
  $('input').addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
  });
  $('modalClose').onclick = () => $('modal').classList.add('hidden');
}

async function send() {
  const question = $('input').value.trim();
  if (!question) return;
  if (!kbs.length) { await loadKbs(); if (!kbs.length) return alert('知识库为空，请先请管理员上传文档'); }
  $('input').value = '';
  $('btnSend').disabled = true;
  appendMsg('user', question);
  const aiEl = appendMsg('ai', '…思考中');
  const bubble = aiEl.querySelector('.bubble');
  const citeBlock = aiEl.querySelector('.cite-block');
  bubble.textContent = '';
  let full = '', citations = [], messageId = null;
  try {
    const resp = await api('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId: curConv ? curConv.id : null, kbIds: selectedKbIds(), question })
    });
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = '', curEvent = '';
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (line.startsWith('event:')) {
          curEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          const payload = JSON.parse(line.slice(5));
          if (curEvent === 'citations') { citations = payload; renderCites(citeBlock, citations); }
          else if (curEvent === 'delta') { full += payload.text; bubble.textContent = full; scroll(); }
          else if (curEvent === 'meta') { messageId = payload.messageId; addFbRow(aiEl, messageId, 0); tagModel(aiEl, payload); }
          else if (curEvent === 'error') { bubble.textContent = '❌ ' + payload.message; }
        }
      }
    }
    if (curEvent === '' && !full && !citations.length && !bubble.textContent) {
      bubble.textContent = '（无回复内容）';
    }
    await loadConvs(); // 刷新标题
  } catch (e) {
    bubble.textContent = '❌ ' + e.message;
  } finally {
    $('btnSend').disabled = false;
    $('input').focus();
  }
}

function scroll() { const m = $('messages'); m.scrollTop = m.scrollHeight; }

function appendMsg(role, text, cites, msgId, rating) {
  const m = $('messages');
  if (m.querySelector('.empty-tip')) m.innerHTML = '';
  const row = document.createElement('div');
  row.className = 'msg-row ' + (role === 'user' ? 'user' : 'ai');
  row.innerHTML = `<div class="bubble">${esc(text)}</div>`;
  if (role === 'ai') {
    const wrap = document.createElement('div');
    wrap.className = 'cite-block hidden';
    row.querySelector('.bubble').after(wrap);
    if (cites && cites.length) {
      const cb = row.querySelector('.cite-block');
      cb.classList.remove('hidden');
      renderCites(cb, cites);
    }
    if (msgId) addFbRow(row, msgId, rating || 0);
  }
  m.appendChild(row);
  scroll();
  return row;
}

function renderCites(box, cites) {
  if (!cites || !cites.length) { box.classList.add('hidden'); box.innerHTML = ''; return; }
  box.classList.remove('hidden');
  box.innerHTML = '<span class="muted">参考来源：</span>' + cites.map(c =>
    `<div class="cite" data-chunk="${c.chunkId}"><b>[${c.refNo}]</b>${esc(c.fileName)}<span class="loc">${esc(c.location)} · 相关度 ${c.score}</span></div>`).join('');
  box.querySelectorAll('.cite').forEach(el => el.onclick = async () => {
    const d = await apiJson('/api/chunks/' + el.dataset.chunk);
    $('modalTitle').textContent = '📄 ' + d.fileName + '（' + d.location + '）';
    $('modalBody').textContent = d.content;
    $('modal').classList.remove('hidden');
  });
}

function tagModel(row, meta) {
  const tag = document.createElement('div');
  tag.className = 'model-tag';
  tag.textContent = `${meta.model} · ${Math.round(meta.latencyMs / 100) / 10}s${meta.hit ? '' : ' · 未命中知识库'}`;
  row.querySelector('.bubble').after(tag);
}

function addFbRow(row, msgId, rating) {
  const div = document.createElement('div');
  div.className = 'fb-row';
  div.innerHTML = `<button class="fb-btn up ${rating === 1 ? 'sel' : ''}">👍 有用</button>
                   <button class="fb-btn down ${rating === -1 ? 'sel' : ''}">👎 没用</button>`;
  row.querySelector('.bubble').after(div);
  div.querySelector('.up').onclick = () => postFb(msgId, 1, div);
  div.querySelector('.down').onclick = () => {
    const reasons = prompt('选择原因（可多选，用逗号分隔：答案错误/没检索到/内容过时/其他）', '');
    if (reasons === null) return;
    postFb(msgId, -1, div, reasons.split(',').filter(Boolean));
  };
}

async function postFb(msgId, rating, div, reasons) {
  await apiJson('/api/messages/' + msgId + '/feedback', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rating, reasons: reasons || [] })
  });
  div.querySelectorAll('.fb-btn').forEach(b => b.classList.remove('sel'));
  div.querySelector(rating === 1 ? '.up' : '.down').classList.add('sel');
}

/* ================= 知识库管理 ================= */
let selKbId = null;

function bindAdmin() {
  $('btnAddKb').onclick = async () => {
    const name = $('kbName').value.trim();
    if (!name) return alert('请输入知识库名称');
    await apiJson('/api/kb', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, visibility: $('kbVis').value })
    });
    $('kbName').value = '';
    await loadKbs(); renderAdmin(); loadKbChecks();
  };
  $('btnUpload').onclick = async () => {
    const files = $('fileInput').files;
    if (!selKbId) return alert('请先选择知识库');
    if (!files.length) return alert('请选择文件');
    for (const f of files) {
      const fd = new FormData();
      fd.append('file', f);
      try {
        await apiJson('/api/documents/upload?kbId=' + selKbId, { method: 'POST', body: fd });
      } catch (e) { alert(f.name + ' 上传失败：' + e.message); }
    }
    $('fileInput').value = '';
    startPollingDocs();
  };
  $('kbSelectForDocs').onchange = e => { selKbId = Number(e.target.value); renderDocs(); };
}

function renderAdmin() {
  const cards = $('kbCards');
  cards.innerHTML = kbs.map(kb =>
    `<div class="kb-card ${selKbId === kb.id ? 'sel' : ''}" data-id="${kb.id}">
       <div class="n">${esc(kb.name)} ${kb.visibility === 'ADMIN_ONLY' ? '🔒' : ''}</div>
       <div class="d">${kb.docCount} 个文档 · ${esc(kb.description || '')}</div>
     </div>`).join('') || '<span class="muted">暂无知识库</span>';
  cards.querySelectorAll('.kb-card').forEach(el => el.onclick = () => {
    selKbId = Number(el.dataset.id);
    renderAdmin(); renderDocs();
  });
  const sel = $('kbSelectForDocs');
  sel.innerHTML = kbs.map(kb => `<option value="${kb.id}" ${kb.id === selKbId ? 'selected' : ''}>${esc(kb.name)}</option>`).join('');
  if (!selKbId && kbs.length) { selKbId = kbs[0].id; sel.value = selKbId; }
  if (selKbId) renderDocs();
}

const STATUS_BADGE = {
  READY: '<span class="badge ok">✔ 已生效</span>',
  PROCESSING: '<span class="badge proc">⏳ 解析中</span>',
  PENDING: '<span class="badge pending">排队中</span>',
  FAILED: '<span class="badge fail">✖ 失败</span>'
};

async function renderDocs() {
  if (!selKbId) return;
  const docs = await apiJson('/api/documents?kbId=' + selKbId);
  $('curKbTip').textContent = '（当前知识库 #' + selKbId + '）';
  $('docRows').innerHTML = docs.map(d => `<tr>
    <td title="${esc(d.errorMsg)}">${esc(d.fileName)}</td>
    <td>${esc(d.fileType)}</td><td>v${d.version}</td>
    <td>${STATUS_BADGE[d.status] || d.status}${d.status === 'FAILED' && d.errorMsg ? '<div class="muted">' + esc(d.errorMsg).slice(0, 60) + '</div>' : ''}</td>
    <td>${d.enabled ? '<span class="badge ok">启用</span>' : '<span class="badge off">停用(旧版)</span>'}</td>
    <td>${esc(d.createdAt).slice(0, 16).replace('T', ' ')}</td>
    <td>
      <button class="btn sm" data-act="toggle" data-id="${d.id}" data-on="${!d.enabled}">${d.enabled ? '停用' : '启用'}</button>
      <button class="btn sm" data-act="reindex" data-id="${d.id}">重新解析</button>
      <button class="btn sm" data-act="del" data-id="${d.id}">删除</button>
    </td></tr>`).join('') || '<tr><td colspan="7" class="muted">暂无文档，请上传</td></tr>';
  $('docRows').querySelectorAll('button').forEach(b => b.onclick = async () => {
    const id = b.dataset.id, act = b.dataset.act;
    if (act === 'toggle') await api('/api/documents/' + id + '/toggle?enabled=' + b.dataset.on, { method: 'POST' });
    if (act === 'reindex') await api('/api/documents/' + id + '/reindex', { method: 'POST' });
    if (act === 'del') { if (!confirm('确认删除该文档及其索引？')) return; await api('/api/documents/' + id, { method: 'DELETE' }); }
    startPollingDocs();
  });
}

/** 有文档处于解析中时轮询刷新 */
function startPollingDocs() {
  clearInterval(pollingTimer);
  renderDocs();
  pollingTimer = setInterval(async () => {
    if (!$('view-admin').classList.contains('hidden') && selKbId) {
      const docs = await apiJson('/api/documents?kbId=' + selKbId);
      renderDocs();
      if (!docs.some(d => d.status === 'PENDING' || d.status === 'PROCESSING')) clearInterval(pollingTimer);
    } else {
      clearInterval(pollingTimer);
    }
  }, 2500);
}

/* ================= 用户管理（仅管理员） ================= */
function bindUsers() {
  const btn = $('btnCreateUser');
  if (!btn) return;
  btn.onclick = async () => {
    const username = $('newUsername').value.trim();
    const password = $('newUserPwd').value;
    if (!username) return alert('请输入用户名');
    if (!password || password.length < 6) return alert('密码至少 6 位');
    try {
      await apiJson('/api/admin/users', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
      });
      $('newUsername').value = '';
      $('newUserPwd').value = '';
      await loadUsers();
    } catch (e) { alert('创建失败：' + e.message); }
  };
}

async function loadUsers() {
  try {
    const users = await apiJson('/api/admin/users');
    const tbody = $('userRows');
    tbody.innerHTML = users.map(u => `<tr>
      <td>${u.id}</td>
      <td>${esc(u.username)}</td>
      <td>${u.role === 'ADMIN' ? '<span class="badge pending">管理员</span>' : '<span class="badge ok">普通用户</span>'}</td>
      <td>
        <button class="btn sm" data-act="reset" data-id="${u.id}" data-name="${esc(u.username)}">重置密码</button>
        ${u.username === ME.username ? '<span class="muted" style="margin-left:8px;">（当前登录）</span>'
          : `<button class="btn sm" data-act="del" data-id="${u.id}" data-name="${esc(u.username)}">删除</button>`}
      </td></tr>`).join('') || '<tr><td colspan="4" class="muted">暂无账号</td></tr>';
    tbody.querySelectorAll('button').forEach(b => b.onclick = async () => {
      const id = b.dataset.id, name = b.dataset.name, act = b.dataset.act;
      if (act === 'reset') {
        const np = prompt(`为账号「${name}」设置新密码（至少 6 位）：`, '');
        if (np === null) return;
        if (np.length < 6) return alert('密码至少 6 位');
        try {
          await apiJson(`/api/admin/users/${id}/password`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ newPassword: np })
          });
          alert('密码已重置');
        } catch (e) { alert('重置失败：' + e.message); }
      }
      if (act === 'del') {
        if (!confirm(`确认删除账号「${name}」？该操作不可恢复。`)) return;
        try {
          await api('/api/admin/users/' + id, { method: 'DELETE' });
          await loadUsers();
        } catch (e) { alert('删除失败：' + e.message); }
      }
    });
  } catch (e) {
    $('userRows').innerHTML = `<tr><td colspan="4" class="muted">加载失败：${esc(e.message)}</td></tr>`;
  }
}

/* ================= 管理员自助改密 ================= */
function bindPwdModal() {
  const openBtn = $('btnChangePwd');
  if (!openBtn) return;
  openBtn.onclick = () => {
    $('oldPwd').value = ''; $('newPwd').value = ''; $('newPwd2').value = ''; $('pwdMsg').textContent = '';
    $('pwdModal').classList.remove('hidden');
    $('oldPwd').focus();
  };
  $('pwdClose').onclick = () => $('pwdModal').classList.add('hidden');
  $('pwdSubmit').onclick = async () => {
    const oldPassword = $('oldPwd').value;
    const newPassword = $('newPwd').value;
    const confirmPwd = $('newPwd2').value;
    if (!oldPassword || !newPassword) { $('pwdMsg').textContent = '旧密码和新密码都不能为空'; return; }
    if (newPassword.length < 6) { $('pwdMsg').textContent = '新密码至少 6 位'; return; }
    if (newPassword !== confirmPwd) { $('pwdMsg').textContent = '两次输入的新密码不一致'; return; }
    try {
      await apiJson('/api/auth/password', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ oldPassword, newPassword })
      });
      alert('密码已修改，请使用新密码重新登录');
      location.href = 'login.html';
    } catch (e) {
      $('pwdMsg').textContent = e.message;
    }
  };
}

/* ================= 看板 ================= */
async function loadDash() {
  const d = await apiJson('/api/admin/dashboard');
  $('statCards').innerHTML = `
    <div class="stat"><div class="v">${d.total7d}</div><div class="k">近 7 日提问量</div></div>
    <div class="stat"><div class="v">${d.hitRate7d}%</div><div class="k">知识库命中率</div></div>
    <div class="stat"><div class="v">${d.adoptRate}%</div><div class="k">答案采纳率（👍/(👍+👎)）</div></div>
    <div class="stat"><div class="v">${d.upCount} / ${d.downCount}</div><div class="k">👍 / 👎 总数</div></div>`;
  const entries = Object.entries(d.daily);
  const max = Math.max(1, ...entries.map(([, v]) => v));
  $('dailyBars').innerHTML = entries.map(([day, v]) =>
    `<div class="bar-col"><div class="bar-v">${v}</div><div class="bar" style="height:${Math.round(v / max * 110)}px"></div><div class="bar-x">${day.slice(5)}</div></div>`).join('');
  $('badRows').innerHTML = d.badCases.map(c => `<tr>
    <td>${esc(c.createdAt).slice(0, 16).replace('T', ' ')}</td>
    <td>${esc(c.question)}</td><td>${esc(c.answer)}</td><td>${esc(c.reason)}</td></tr>`).join('')
    || '<tr><td colspan="4" class="muted">暂无 BadCase</td></tr>';
}
