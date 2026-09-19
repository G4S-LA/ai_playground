const $ = (selector) => document.querySelector(selector);
const labels = {
  architecture: "Архитектура", technical_decision: "Техническое решение",
  stack_constraint: "Ограничение стека", business_rule: "Бизнес-правило",
  goal: "Цель", context: "Контекст", constraint: "Ограничение", note: "Заметка",
  profile: "Профиль", decision: "Решение", knowledge: "Знание",
};
let currentSessionId = null;
let currentSnapshot = null;
let categories = {memory: {}, invariants: []};
let sessions = [];
let busy = false;

const sessionUrl = (suffix = "") => `/api/sessions/${encodeURIComponent(currentSessionId)}${suffix}`;
const jsonOptions = (method, body) => ({method, headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || "Ошибка запроса");
  return body;
}

function setBusy(value) {
  busy = value;
  document.querySelectorAll("button, textarea, select").forEach((element) => element.disabled = value);
  $("#send-button").textContent = value ? "Проверяю…" : "Отправить";
}

async function run(action) {
  if (busy) return;
  setBusy(true);
  try { await action(); } catch (error) { addMessage("error", error.message); } finally { setBusy(false); }
}

function addMessage(role, text, compliance = null) {
  const box = document.createElement("article");
  box.className = `message message--${role}`;
  const content = document.createElement("div");
  content.textContent = text;
  box.appendChild(content);
  if (role === "assistant" && compliance) {
    const details = document.createElement("details");
    details.className = `audit audit--${compliance.status}`;
    const summary = document.createElement("summary");
    summary.textContent = `Проверка: ${compliance.status} · ${compliance.summary}`;
    details.appendChild(summary);
    for (const check of compliance.checks) {
      const row = document.createElement("p");
      row.textContent = `${check.status}: ${check.explanation}`;
      details.appendChild(row);
    }
    box.appendChild(details);
  }
  $("#messages").appendChild(box);
  $("#messages").scrollTop = $("#messages").scrollHeight;
  return box;
}

function renderMessages(items) {
  $("#messages").replaceChildren();
  if (!items.length) addMessage("system", "Добавьте инвариант и попросите ассистента предложить решение.");
  for (const item of items) {
    if (item.role === "user" || item.role === "assistant") addMessage(item.role, item.content, item.compliance);
  }
}

function itemNode(item, onDelete) {
  const row = document.createElement("div");
  row.className = "item";
  const text = document.createElement("div");
  const category = document.createElement("strong");
  category.textContent = labels[item.category] || item.category;
  const content = document.createElement("span");
  content.textContent = item.content;
  text.append(category, content);
  const remove = document.createElement("button");
  remove.type = "button";
  remove.className = "remove";
  remove.textContent = "×";
  remove.title = "Удалить";
  remove.addEventListener("click", () => run(onDelete));
  row.append(text, remove);
  return row;
}

function renderList(selector, items, onDelete) {
  const target = $(selector);
  target.replaceChildren();
  if (!items.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "Пока пусто";
    target.appendChild(empty);
  }
  for (const item of items) target.appendChild(itemNode(item, () => onDelete(item.id)));
}

function renderSnapshot(snapshot) {
  currentSnapshot = snapshot;
  currentSessionId = snapshot.session.id;
  $("#session-title").textContent = snapshot.session.title;
  $("#model-name").textContent = `Модель: ${snapshot.model}`;
  renderMessages(snapshot.session.messages || []);
  $("#invariant-count").textContent = snapshot.invariants.length;
  $("#working-count").textContent = snapshot.working.length;
  $("#long-count").textContent = snapshot.longTerm.length;
  renderList("#invariant-list", snapshot.invariants, removeInvariant);
  renderList("#working-list", snapshot.working, (id) => forget("working", id));
  renderList("#long-list", snapshot.longTerm, (id) => forget("long_term", id));
}

async function refreshSessions() {
  sessions = (await requestJson("/api/sessions")).sessions;
  const target = $("#session-list");
  target.replaceChildren();
  for (const session of sessions) {
    const button = document.createElement("button");
    button.className = session.id === currentSessionId ? "is-active" : "";
    button.textContent = `${session.title} · ${session.messageCount}`;
    button.addEventListener("click", () => run(() => selectSession(session.id)));
    target.appendChild(button);
  }
}

async function selectSession(id) {
  renderSnapshot((await requestJson(`/api/sessions/${encodeURIComponent(id)}`)).snapshot);
  await refreshSessions();
}

function fillSelect(select, values) {
  select.replaceChildren();
  for (const value of values) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = labels[value] || value;
    select.appendChild(option);
  }
}

function updateMemoryCategories() {
  fillSelect($("#memory-category"), categories.memory[$("#memory-layer").value] || []);
}

async function removeInvariant(id) {
  renderSnapshot((await requestJson(sessionUrl(`/invariants/${encodeURIComponent(id)}`), {method: "DELETE"})).snapshot);
}

async function forget(layer, id) {
  renderSnapshot((await requestJson(sessionUrl(`/memories/${layer}/${encodeURIComponent(id)}`), {method: "DELETE"})).snapshot);
}

$("#memory-layer").addEventListener("change", updateMemoryCategories);
$("#message-input").addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); $("#chat-form").requestSubmit(); }
});

$("#chat-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const message = $("#message-input").value.trim();
  if (!message) return;
  run(async () => {
    $("#message-input").value = "";
    const result = await requestJson(sessionUrl("/messages"), jsonOptions("POST", {message}));
    renderSnapshot(result.snapshot);
    await refreshSessions();
  });
});

$("#invariant-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const content = $("#invariant-content").value.trim();
  if (!content) return;
  run(async () => {
    const result = await requestJson(sessionUrl("/invariants"), jsonOptions("POST", {
      category: $("#invariant-category").value, content,
    }));
    $("#invariant-content").value = "";
    renderSnapshot(result.snapshot);
  });
});

$("#memory-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const content = $("#memory-content").value.trim();
  if (!content) return;
  run(async () => {
    const result = await requestJson(sessionUrl("/memories"), jsonOptions("POST", {
      layer: $("#memory-layer").value, category: $("#memory-category").value, content,
    }));
    $("#memory-content").value = "";
    renderSnapshot(result.snapshot);
  });
});

$("#new-session").addEventListener("click", () => run(async () => {
  const result = await requestJson("/api/sessions", jsonOptions("POST", {}));
  await selectSession(result.session.id);
}));

$("#delete-session").addEventListener("click", () => {
  if (!confirm("Удалить диалог и его рабочую память? Инварианты останутся.")) return;
  run(async () => {
    await requestJson(sessionUrl(), {method: "DELETE"});
    await refreshSessions();
    const next = sessions[0] || (await requestJson("/api/sessions", jsonOptions("POST", {}))).session;
    await selectSession(next.id);
  });
});

run(async () => {
  categories = await requestJson("/api/categories");
  fillSelect($("#invariant-category"), categories.invariants);
  updateMemoryCategories();
  await refreshSessions();
  const first = sessions[0] || (await requestJson("/api/sessions", jsonOptions("POST", {}))).session;
  await selectSession(first.id);
});
