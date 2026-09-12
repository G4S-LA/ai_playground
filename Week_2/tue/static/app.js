const chatList = document.querySelector("#chat-list");
const chatTitle = document.querySelector("#chat-title");
const messages = document.querySelector("#messages");
const form = document.querySelector("#chat-form");
const input = document.querySelector("#message-input");
const sendButton = document.querySelector("#send-button");
const newChatButton = document.querySelector("#new-chat-button");
const deleteButton = document.querySelector("#delete-button");

let currentChatId = null;
let knownChats = [];

function addMessage(role, text) {
  const element = document.createElement("div");
  element.className = `message message--${role}`;
  element.textContent = text;
  messages.appendChild(element);
  messages.scrollTop = messages.scrollHeight;
}

function renderMessages(history) {
  messages.replaceChildren();
  if (history.length === 0) {
    addMessage("system", "Это новый чат. Напишите первое сообщение.");
    return;
  }
  for (const message of history) {
    const role = message.role === "assistant" ? "agent" : "user";
    addMessage(role, message.content);
  }
}

function renderChatList() {
  chatList.replaceChildren();
  for (const chat of knownChats) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "chat-list__item";
    button.disabled = input.disabled;
    if (chat.id === currentChatId) button.classList.add("is-active");

    const title = document.createElement("span");
    title.className = "chat-list__title";
    title.textContent = chat.title;

    const count = document.createElement("span");
    count.className = "chat-list__count";
    count.textContent = `${chat.message_count} сообщ.`;

    button.append(title, count);
    button.addEventListener("click", () => selectChat(chat.id));
    chatList.appendChild(button);
  }
}

function setBusy(isBusy) {
  input.disabled = isBusy;
  sendButton.disabled = isBusy;
  newChatButton.disabled = isBusy;
  deleteButton.disabled = isBusy || currentChatId === null;
  for (const button of chatList.querySelectorAll("button")) {
    button.disabled = isBusy;
  }
  sendButton.textContent = isBusy ? "Ждём ответ..." : "Отправить";
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const result = await response.json();
  if (!response.ok) {
    throw new Error(result.error || "Не удалось выполнить запрос");
  }
  return result;
}

async function refreshChatList() {
  const result = await requestJson("/api/chats");
  knownChats = result.chats;
  renderChatList();
}

async function selectChat(chatId) {
  currentChatId = chatId;
  renderChatList();
  setBusy(true);
  try {
    const result = await requestJson(
      `/api/chats/${encodeURIComponent(chatId)}/messages`,
    );
    chatTitle.textContent = result.chat.title;
    renderMessages(result.messages);
  } catch (error) {
    addMessage("error", `Ошибка: ${error.message}`);
  } finally {
    setBusy(false);
    input.focus();
  }
}

async function createChat() {
  setBusy(true);
  try {
    const result = await requestJson("/api/chats", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ discard_empty_chat_id: currentChatId }),
    });
    await refreshChatList();
    await selectChat(result.chat.id);
  } catch (error) {
    addMessage("error", `Ошибка: ${error.message}`);
    setBusy(false);
  }
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const message = input.value.trim();
  if (!message || currentChatId === null) return;

  addMessage("user", message);
  input.value = "";
  setBusy(true);

  try {
    const result = await requestJson(
      `/api/chats/${encodeURIComponent(currentChatId)}/messages`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message }),
      },
    );
    addMessage("agent", result.answer);
    chatTitle.textContent = result.chat.title;
    await refreshChatList();
  } catch (error) {
    addMessage("error", `Ошибка: ${error.message}`);
  } finally {
    setBusy(false);
    input.focus();
  }
});

input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    form.requestSubmit();
  }
});

newChatButton.addEventListener("click", createChat);

deleteButton.addEventListener("click", async () => {
  if (currentChatId === null) return;
  if (!window.confirm("Полностью удалить текущий чат и все его сообщения?")) {
    return;
  }

  setBusy(true);
  try {
    await requestJson(
      `/api/chats/${encodeURIComponent(currentChatId)}`,
      { method: "DELETE" },
    );
    currentChatId = null;
    chatTitle.textContent = "Чат удалён";
    renderMessages([]);
    await refreshChatList();
    if (knownChats.length === 0) {
      await createChat();
    } else {
      await selectChat(knownChats[0].id);
    }
  } catch (error) {
    addMessage("error", `Ошибка: ${error.message}`);
  } finally {
    setBusy(false);
    input.focus();
  }
});

async function initialize() {
  setBusy(true);
  try {
    await refreshChatList();
    if (knownChats.length === 0) {
      await createChat();
    } else {
      await selectChat(knownChats[0].id);
    }
  } catch (error) {
    chatTitle.textContent = "Ошибка загрузки";
    addMessage("error", `Ошибка: ${error.message}`);
    setBusy(false);
  }
}

initialize();
