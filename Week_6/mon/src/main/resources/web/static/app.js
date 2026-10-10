const $ = (selector) => document.querySelector(selector);

const chatList = $("#chat-list");
const messages = $("#messages");
const input = $("#message-input");
const sendButton = $("#send-button");

let currentChatId = null;
let knownChats = [];
let busy = false;

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  if (response.status === 204) return null;
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
  return payload;
}

function jsonOptions(method, body = {}) {
  return {
    method,
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(body),
  };
}

function setBusy(value) {
  busy = value;
  input.disabled = value || currentChatId === null;
  sendButton.disabled = value || currentChatId === null;
  $("#new-chat").disabled = value;
  $("#delete-chat").disabled = value || currentChatId === null;
  for (const button of chatList.querySelectorAll("button")) button.disabled = value;
  sendButton.querySelector("span").textContent = value ? "Модель думает…" : "Отправить";
}

function toast(message, isError = false) {
  const element = $("#toast");
  element.textContent = message;
  element.className = `toast toast--visible${isError ? " toast--error" : ""}`;
  clearTimeout(toast.timer);
  toast.timer = window.setTimeout(() => element.className = "toast", 4200);
}

function addMessage(role, content, {pending = false} = {}) {
  const wrapper = document.createElement("article");
  wrapper.className = `message message--${role}${pending ? " message--pending" : ""}`;

  const avatar = document.createElement("span");
  avatar.className = "message__avatar";
  avatar.textContent = role === "user" ? "Вы" : role === "assistant" ? "LLM" : "i";

  const text = document.createElement("p");
  text.textContent = content;
  wrapper.append(avatar, text);
  messages.appendChild(wrapper);
  messages.scrollTop = messages.scrollHeight;
  return wrapper;
}

function renderMessages(history) {
  messages.replaceChildren();
  if (!history.length) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    const icon = document.createElement("span");
    icon.textContent = "✦";
    const title = document.createElement("h3");
    title.textContent = "Диалог готов";
    const text = document.createElement("p");
    text.textContent = "Сообщения отправляются в Ollama на этом компьютере и сохраняются локально.";
    empty.append(icon, title, text);
    messages.appendChild(empty);
    return;
  }
  for (const message of history) addMessage(message.role, message.content);
}

function renderChatList() {
  chatList.replaceChildren();
  $("#chat-count").textContent = knownChats.length;
  for (const chat of knownChats) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `chat-item${chat.id === currentChatId ? " is-active" : ""}`;
    button.disabled = busy;

    const icon = document.createElement("span");
    icon.className = "chat-item__icon";
    icon.textContent = "◌";
    const copy = document.createElement("span");
    copy.className = "chat-item__copy";
    const title = document.createElement("strong");
    title.textContent = chat.title;
    const meta = document.createElement("small");
    meta.textContent = `${chat.messageCount} сообщ. · ${formatDate(chat.updatedAt)}`;
    copy.append(title, meta);
    button.append(icon, copy);
    button.addEventListener("click", () => runAction(() => selectChat(chat.id)));
    chatList.appendChild(button);
  }
}

function formatDate(value) {
  const date = new Date(value);
  const today = new Date();
  if (date.toDateString() === today.toDateString()) {
    return date.toLocaleTimeString("ru-RU", {hour: "2-digit", minute: "2-digit"});
  }
  return date.toLocaleDateString("ru-RU", {day: "2-digit", month: "short"});
}

async function refreshChats() {
  knownChats = (await requestJson("/api/chats")).chats;
  renderChatList();
}

async function selectChat(id) {
  const {chat} = await requestJson(`/api/chats/${encodeURIComponent(id)}`);
  currentChatId = chat.id;
  $("#chat-title").textContent = chat.title;
  renderMessages(chat.messages);
  await refreshChats();
  input.focus();
}

async function createChat() {
  const {chat} = await requestJson("/api/chats", jsonOptions("POST"));
  currentChatId = chat.id;
  await refreshChats();
  await selectChat(chat.id);
}

async function loadStatus() {
  const dot = $("#status-dot");
  try {
    const info = await requestJson("/api/info");
    $("#model-name").textContent = `Ollama · ${info.model}`;
    $("#status-details").textContent = info.model;
    if (info.status.reachable && info.status.installed) {
      dot.className = "status-dot status-dot--online";
      $("#status-title").textContent = "Готова локально";
    } else if (info.status.reachable) {
      dot.className = "status-dot status-dot--warning";
      $("#status-title").textContent = "Модель не установлена";
    } else {
      throw new Error(info.status.error || "Ollama недоступна");
    }
  } catch (error) {
    dot.className = "status-dot status-dot--offline";
    $("#status-title").textContent = "Ollama недоступна";
    $("#status-details").textContent = "Запустите ollama serve";
    toast(error.message, true);
  }
}

async function runAction(action) {
  if (busy) return;
  setBusy(true);
  try {
    await action();
  } catch (error) {
    toast(error.message, true);
  } finally {
    setBusy(false);
    input.focus();
  }
}

$("#chat-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const text = input.value.trim();
  if (!text || !currentChatId || busy) return;

  runAction(async () => {
    if (messages.querySelector(".empty-state")) messages.replaceChildren();
    addMessage("user", text);
    input.value = "";
    const pending = addMessage("assistant", "Формирую ответ локально…", {pending: true});
    try {
      const reply = await requestJson(
        `/api/chats/${encodeURIComponent(currentChatId)}/messages`,
        jsonOptions("POST", {message: text}),
      );
      pending.remove();
      addMessage("assistant", reply.message.content);
      $("#chat-title").textContent = reply.chat.title;
      await refreshChats();
    } catch (error) {
      pending.remove();
      input.value = text;
      throw error;
    }
  });
});

input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    $("#chat-form").requestSubmit();
  }
});

$("#new-chat").addEventListener("click", () => runAction(createChat));
$("#refresh-status").addEventListener("click", loadStatus);

$("#delete-chat").addEventListener("click", () => {
  if (!currentChatId || !window.confirm("Удалить этот диалог вместе со всей историей?")) return;
  runAction(async () => {
    await requestJson(`/api/chats/${encodeURIComponent(currentChatId)}`, {method: "DELETE"});
    currentChatId = null;
    await refreshChats();
    if (knownChats.length) await selectChat(knownChats[0].id);
    else await createChat();
    toast("Диалог удалён");
  });
});

runAction(async () => {
  await Promise.all([refreshChats(), loadStatus()]);
  if (knownChats.length) await selectChat(knownChats[0].id);
  else await createChat();
});
