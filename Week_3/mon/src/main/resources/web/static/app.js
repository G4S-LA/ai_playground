const $ = (selector) => document.querySelector(selector);
const messages = $("#messages");
const input = $("#message-input");
const layerSelect = $("#memory-layer");
const categorySelect = $("#memory-category");

const categoryLabels = {
  dialogue_note: "Заметка диалога",
  goal: "Цель",
  context: "Контекст",
  constraint: "Ограничение",
  note: "Заметка",
  profile: "Профиль",
  decision: "Решение",
  knowledge: "Знание",
};
const roleLabels = {user: "Вы", assistant: "Агент", memory: "Заметка"};
let categories = {};
let currentSessionId = null;
let currentSnapshot = null;
let knownSessions = [];
let busy = false;

const sessionUrl = (suffix = "") =>
  `/api/sessions/${encodeURIComponent(currentSessionId)}${suffix}`;
const jsonOptions = (method, body) => ({
  method,
  headers: {"Content-Type": "application/json"},
  body: JSON.stringify(body),
});

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || "Не удалось выполнить запрос.");
  return body;
}

function setBusy(value) {
  busy = value;
  for (const element of [
    input, $("#send-button"), $("#new-session"), $("#delete-session"),
    layerSelect, categorySelect, $("#memory-content"), $("#remember-button"),
  ]) element.disabled = value || !currentSessionId;
  for (const button of document.querySelectorAll("#session-list button, .memory-item__delete")) {
    button.disabled = value;
  }
  $("#send-button").textContent = value ? "Подождите…" : "Отправить";
}

async function runAction(action) {
  if (busy) return;
  setBusy(true);
  try {
    await action();
  } catch (error) {
    addMessage("error", error.message);
  } finally {
    setBusy(false);
  }
}

function addMessage(role, text, category = null) {
  const wrapper = document.createElement("div");
  wrapper.className = `message message--${role}`;
  if (category) {
    const badge = document.createElement("span");
    badge.className = "message__badge";
    badge.textContent = categoryLabels[category] || category;
    wrapper.appendChild(badge);
  }
  const content = document.createElement("span");
  content.textContent = text;
  wrapper.appendChild(content);
  messages.appendChild(wrapper);
  messages.scrollTop = messages.scrollHeight;
  return wrapper;
}

function renderMessages(history) {
  messages.replaceChildren();
  if (!history.length) {
    addMessage("system", "Напишите первое сообщение или явно добавьте запись в нужный слой памяти.");
    return;
  }
  for (const item of history) {
    const role = item.role === "assistant" ? "agent" : item.role;
    addMessage(role, item.content, item.category);
  }
}

function memoryItem(item, layer, prefix = "") {
  const row = document.createElement("div");
  row.className = "memory-item";
  const text = document.createElement("div");
  const category = document.createElement("strong");
  category.textContent = prefix || categoryLabels[item.category] || item.category;
  const content = document.createElement("span");
  content.textContent = item.content;
  text.append(category, content);
  row.appendChild(text);
  if (item.deletable !== false) {
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "memory-item__delete";
    remove.title = "Удалить запись из памяти";
    remove.setAttribute("aria-label", "Удалить запись из памяти");
    remove.textContent = "×";
    remove.addEventListener("click", () => runAction(() => forget(layer, item.id)));
    row.appendChild(remove);
  }
  return row;
}

function renderLayer(target, items, layer, countTarget) {
  target.replaceChildren();
  $(countTarget).textContent = items.length;
  if (!items.length) {
    const empty = document.createElement("p");
    empty.className = "memory-empty";
    empty.textContent = "Пока пусто";
    target.appendChild(empty);
    return;
  }
  for (const item of items) target.appendChild(memoryItem(item, layer, item.prefix));
}

function renderSnapshot(snapshot) {
  currentSnapshot = snapshot;
  currentSessionId = snapshot.session.id;
  $("#session-title").textContent = snapshot.session.title;
  $("#model-name").textContent = `Модель: ${snapshot.model}`;
  renderMessages(snapshot.session.messages);

  const shortItems = snapshot.session.messages.slice(-5).map((item) => ({
    ...item,
    prefix: item.role === "memory"
      ? (categoryLabels[item.category] || item.category)
      : roleLabels[item.role],
    deletable: item.role === "memory",
  }));
  renderLayer($("#short-memory"), shortItems, "short_term", "#short-count");
  $("#short-count").textContent = snapshot.session.messages.length;
  renderLayer($("#working-memory"), snapshot.working, "working", "#working-count");
  renderLayer($("#long-memory"), snapshot.longTerm, "long_term", "#long-count");
}

async function refreshSessions() {
  knownSessions = (await requestJson("/api/sessions")).sessions;
  const list = $("#session-list");
  list.replaceChildren();
  for (const session of knownSessions) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "session-list__item" + (session.id === currentSessionId ? " is-active" : "");
    const title = document.createElement("span");
    title.className = "session-list__title";
    title.textContent = session.title;
    const count = document.createElement("span");
    count.className = "session-list__count";
    count.textContent = session.messageCount;
    button.append(title, count);
    button.addEventListener("click", () => runAction(() => selectSession(session.id)));
    list.appendChild(button);
  }
}

async function selectSession(id) {
  const result = await requestJson(`/api/sessions/${encodeURIComponent(id)}`);
  renderSnapshot(result.snapshot);
  input.value = "";
  await refreshSessions();
}

function updateCategories() {
  categorySelect.replaceChildren();
  for (const category of categories[layerSelect.value] || []) {
    const option = document.createElement("option");
    option.value = category;
    option.textContent = categoryLabels[category] || category;
    categorySelect.appendChild(option);
  }
}

async function forget(layer, memoryId) {
  const result = await requestJson(
    sessionUrl(`/memories/${encodeURIComponent(layer)}/${encodeURIComponent(memoryId)}`),
    {method: "DELETE"},
  );
  renderSnapshot(result.snapshot);
  $("#memory-status").textContent = "Запись удалена.";
  await refreshSessions();
}

layerSelect.addEventListener("change", updateCategories);

input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    $("#chat-form").requestSubmit();
  }
});

$("#chat-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const message = input.value.trim();
  if (!message || busy) return;
  runAction(async () => {
    if (!currentSnapshot.session.messages.length) messages.replaceChildren();
    addMessage("user", message);
    const pending = addMessage("agent", "Модель собирает prompt из трёх слоёв памяти…");
    pending.classList.add("message--pending");
    input.value = "";
    try {
      const result = await requestJson(sessionUrl("/messages"), jsonOptions("POST", {message}));
      renderSnapshot(result.snapshot);
      await refreshSessions();
    } catch (error) {
      pending.remove();
      input.value = message;
      throw error;
    }
  });
});

$("#memory-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const content = $("#memory-content").value.trim();
  if (!content || busy) return;
  runAction(async () => {
    const body = {layer: layerSelect.value, category: categorySelect.value, content};
    const result = await requestJson(sessionUrl("/memories"), jsonOptions("POST", body));
    renderSnapshot(result.snapshot);
    $("#memory-content").value = "";
    $("#memory-status").textContent = `Сохранено: ${categoryLabels[body.category] || body.category}.`;
    await refreshSessions();
  });
});

$("#new-session").addEventListener("click", () => runAction(async () => {
  const result = await requestJson("/api/sessions", jsonOptions("POST", {}));
  await selectSession(result.session.id);
}));

$("#delete-session").addEventListener("click", () => {
  if (!window.confirm("Удалить текущий диалог и его рабочую память? Долговременная память сохранится.")) return;
  runAction(async () => {
    await requestJson(sessionUrl(), {method: "DELETE"});
    await refreshSessions();
    const next = knownSessions[0] || (await requestJson("/api/sessions", jsonOptions("POST", {}))).session;
    await selectSession(next.id);
  });
});

runAction(async () => {
  categories = (await requestJson("/api/categories")).categories;
  updateCategories();
  await refreshSessions();
  const initial = knownSessions[0] || (await requestJson("/api/sessions", jsonOptions("POST", {}))).session;
  await selectSession(initial.id);
});
