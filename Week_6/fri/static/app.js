const form = document.querySelector("#chat-form");
const promptInput = document.querySelector("#prompt");
const keyInput = document.querySelector("#api-key");
const saveKeyButton = document.querySelector("#save-key");
const newChatButton = document.querySelector("#new-chat");
const deleteChatButton = document.querySelector("#delete-chat");
const sendButton = document.querySelector("#send");
const messagesElement = document.querySelector("#messages");
const chatListElement = document.querySelector("#chat-list");
const chatTitleElement = document.querySelector("#chat-title");
const statusElement = document.querySelector("#status");
const modelInfoElement = document.querySelector("#model-info");
const counterElement = document.querySelector("#counter");

let currentChat = null;
let busy = false;

keyInput.value = sessionStorage.getItem("local-chat-key") || "";

function apiKey() {
  return keyInput.value.trim();
}

async function api(path, options = {}) {
  const key = apiKey();
  if (!key) throw new Error("Введите SERVICE_API_KEY из файла .env");
  const response = await fetch(path, {
    ...options,
    headers: {
      "Authorization": `Bearer ${key}`,
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(options.headers || {}),
    },
  });
  if (response.status === 204) return null;
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error?.message || `HTTP ${response.status}`);
  return payload;
}

function showError(message) {
  const element = document.createElement("article");
  element.className = "message error";
  element.textContent = message;
  messagesElement.append(element);
  messagesElement.scrollTop = messagesElement.scrollHeight;
}

function renderMessages() {
  messagesElement.replaceChildren();
  if (!currentChat || currentChat.messages.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = currentChat
      ? "Напишите первое сообщение."
      : "Создайте или выберите диалог.";
    messagesElement.append(empty);
    return;
  }
  for (const message of currentChat.messages) {
    const article = document.createElement("article");
    article.className = `message ${message.role}`;
    const role = document.createElement("span");
    role.className = "role";
    role.textContent = message.role === "user" ? "Вы" : "Локальная модель";
    const content = document.createElement("p");
    content.textContent = message.content;
    article.append(role, content);
    messagesElement.append(article);
  }
  messagesElement.scrollTop = messagesElement.scrollHeight;
}

function renderChatList(chats) {
  chatListElement.replaceChildren();
  if (chats.length === 0) {
    const empty = document.createElement("p");
    empty.className = "muted sidebar-empty";
    empty.textContent = "Диалогов пока нет";
    chatListElement.append(empty);
    return;
  }
  for (const chat of chats) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `chat-item${currentChat?.id === chat.id ? " active" : ""}`;
    const title = document.createElement("span");
    title.textContent = chat.title;
    const count = document.createElement("small");
    count.textContent = `${chat.messageCount} сообщ.`;
    button.append(title, count);
    button.addEventListener("click", () => selectChat(chat.id));
    chatListElement.append(button);
  }
}

function setCurrentChat(chat) {
  currentChat = chat;
  chatTitleElement.textContent = chat?.title || "Выберите диалог";
  deleteChatButton.disabled = !chat || busy;
  promptInput.disabled = !chat || busy;
  sendButton.disabled = !chat || busy;
  renderMessages();
}

function setBusy(value) {
  busy = value;
  newChatButton.disabled = value;
  saveKeyButton.disabled = value;
  deleteChatButton.disabled = value || !currentChat;
  promptInput.disabled = value || !currentChat;
  sendButton.disabled = value || !currentChat;
  sendButton.textContent = value ? "Модель отвечает…" : "Отправить";
}

async function loadChats(selectFirst = false) {
  const payload = await api("/api/chats");
  renderChatList(payload.chats);
  if (selectFirst && !currentChat && payload.chats.length > 0) {
    await selectChat(payload.chats[0].id);
  }
}

async function selectChat(chatId) {
  try {
    const payload = await api(`/api/chats/${chatId}`);
    setCurrentChat(payload.chat);
    await loadChats();
  } catch (error) {
    showError(error.message);
  }
}

async function createChat() {
  try {
    setBusy(true);
    const payload = await api("/api/chats", { method: "POST", body: "{}" });
    setCurrentChat(payload.chat);
    await loadChats();
    promptInput.focus();
  } catch (error) {
    showError(error.message);
  } finally {
    setBusy(false);
  }
}

async function deleteCurrentChat() {
  if (!currentChat || !window.confirm(`Удалить диалог «${currentChat.title}»?`)) return;
  try {
    setBusy(true);
    await api(`/api/chats/${currentChat.id}`, { method: "DELETE" });
    setCurrentChat(null);
    await loadChats(true);
  } catch (error) {
    showError(error.message);
  } finally {
    setBusy(false);
  }
}

async function sendMessage(content) {
  if (!currentChat) return;
  const chatId = currentChat.id;
  try {
    setBusy(true);
    const payload = await api(`/api/chats/${chatId}/messages`, {
      method: "POST",
      body: JSON.stringify({ message: content }),
    });
    setCurrentChat(payload.chat);
    await loadChats();
  } catch (error) {
    showError(error.message);
  } finally {
    setBusy(false);
    promptInput.focus();
  }
}

async function connect() {
  const key = apiKey();
  if (!key) {
    showError("Введите SERVICE_API_KEY из файла .env");
    return;
  }
  sessionStorage.setItem("local-chat-key", key);
  try {
    await loadChats(true);
    saveKeyButton.textContent = "Подключено";
  } catch (error) {
    showError(error.message);
    saveKeyButton.textContent = "Подключиться";
  }
}

async function updateHealth() {
  try {
    const response = await fetch("/health", { cache: "no-store" });
    const health = await response.json();
    statusElement.className = `status ${health.ready ? "ok" : "error"}`;
    statusElement.textContent = health.ready ? "Модель готова" : "Ollama не готова";
    modelInfoElement.textContent = `${health.model} · диалоги сохраняются в ${health.storage}`;
  } catch (error) {
    statusElement.className = "status error";
    statusElement.textContent = "Сервис недоступен";
    modelInfoElement.textContent = error.message;
  }
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  const content = promptInput.value.trim();
  if (!content) return;
  promptInput.value = "";
  counterElement.textContent = "0 символов";
  sendMessage(content);
});

promptInput.addEventListener("input", () => {
  counterElement.textContent = `${promptInput.value.length} символов`;
});

promptInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
    event.preventDefault();
    form.requestSubmit();
  }
});

saveKeyButton.addEventListener("click", connect);
newChatButton.addEventListener("click", createChat);
deleteChatButton.addEventListener("click", deleteCurrentChat);

updateHealth();
if (apiKey()) connect();
