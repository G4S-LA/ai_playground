const chatList = document.querySelector("#chat-list");
const chatTitle = document.querySelector("#chat-title");
const messages = document.querySelector("#messages");
const form = document.querySelector("#chat-form");
const input = document.querySelector("#message-input");
const sendButton = document.querySelector("#send-button");
const newChatButton = document.querySelector("#new-chat-button");
const deleteButton = document.querySelector("#delete-button");
const contextWindow = document.querySelector("#context-window");
const tokenPreview = document.querySelector("#token-preview");
const contextProgress = document.querySelector("#context-progress");
const usageTotals = document.querySelector("#usage-totals");
const usageRows = document.querySelector("#usage-rows");
const outputBudget = document.querySelector("#output-budget");
const contextBreakdown = document.querySelector("#context-breakdown");
const tokenDetails = document.querySelector("#token-details");
const number = new Intl.NumberFormat("ru-RU");
let previewTimer;
let previewVersion = 0;

function money(value) {
  return value === null ? "—" : value.toFixed(6);
}

function renderStatistics(stats) {
  const total = stats.totals;
  contextWindow.value = stats.context_window_tokens;
  outputBudget.textContent = `Резерв на ответ: ${number.format(stats.max_output_tokens)} токенов`;
  let cost = `Стоимость по тарифам: $${money(total.known_cost_usd)}`;
  if (total.turn_count === 0) {
    cost = "Стоимость появится после ответа";
  } else if (total.unpriced_turns === total.turn_count) {
    cost = "Стоимость не рассчитана — тарифы не заданы полностью";
  } else if (total.unpriced_turns) {
    cost = `Известная стоимость: $${money(total.known_cost_usd)}; без цены: ${total.unpriced_turns} ход.`;
  }
  usageTotals.textContent = `Расход всего диалога: ${total.has_estimates ? "≈" : ""}` +
    `${number.format(total.total_tokens)} токенов · ${cost}`;
  tokenDetails.textContent = `Сохранённая история: ≈${number.format(stats.history_tokens_estimate)} токенов. ` +
    `Всего отправлено: ${number.format(total.input_tokens)}; сгенерировано: ${number.format(total.output_tokens)}.` +
    (stats.untracked_turns ? ` · Старых ходов без статистики: ${stats.untracked_turns}` : "");
  usageRows.replaceChildren();
  let cumulativeTokens = 0;
  let cumulativeCost = 0;
  let incompleteCost = false;
  let estimatedTotal = false;
  stats.turns.forEach((turn, index) => {
    cumulativeTokens += turn.total_tokens;
    cumulativeCost += turn.cost_usd ?? 0;
    incompleteCost ||= turn.cost_usd === null;
    estimatedTotal ||= turn.input_source !== "api" || turn.output_source !== "api";
    const row = document.createElement("tr");
    const values = [
      `${index + 1}${turn.finish_reason === "length" ? " ⚠" : ""}`,
      turn.request_tokens_estimate, turn.history_after_tokens_estimate,
      `${turn.input_source === "api" ? "" : "≈"}${turn.input_tokens}`,
      `${turn.output_source === "api" ? "" : "≈"}${turn.output_tokens}`,
      `${estimatedTotal ? "≈" : ""}${cumulativeTokens}`, money(turn.cost_usd),
      incompleteCost ? `≥${money(cumulativeCost)}` : money(cumulativeCost),
    ];
    for (const value of values) {
      const cell = document.createElement("td");
      cell.textContent = value;
      row.appendChild(cell);
    }
    usageRows.appendChild(row);
  });
}

function renderPreview(preview) {
  tokenPreview.textContent = `Контекст с резервом: ≈${number.format(preview.required_tokens)} ` +
    `из ${number.format(preview.context_window_tokens)} токенов`;
  contextBreakdown.textContent = `Вход ≈${number.format(preview.input_tokens_estimate)} ` +
    `(новый вопрос ≈${number.format(preview.request_tokens_estimate)}) + ` +
    `резерв ответа ${number.format(preview.max_output_tokens)}. ` +
    (preview.fits
      ? `Свободно ≈${number.format(preview.context_window_tokens - preview.required_tokens)}.`
      : `Переполнение на ≈${number.format(preview.required_tokens - preview.context_window_tokens)} токенов — отправка невозможна.`);
  tokenPreview.classList.toggle("is-overflow", !preview.fits);
  contextBreakdown.classList.toggle("is-overflow", !preview.fits);
  contextProgress.classList.toggle("is-overflow", !preview.fits);
  contextProgress.max = preview.context_window_tokens;
  contextProgress.value = Math.min(preview.required_tokens, preview.context_window_tokens);
}

async function refreshPreview() {
  const version = ++previewVersion;
  const chatId = currentChatId;
  if (chatId === null || input.disabled) return;
  const tokens = Number(contextWindow.value);
  if (!Number.isSafeInteger(tokens) || tokens <= 0) {
    tokenPreview.textContent = "Размер окна должен быть положительным целым числом.";
    return;
  }
  try {
    const result = await requestJson(`/api/chats/${encodeURIComponent(chatId)}/preview`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: input.value, context_window_tokens: tokens }),
    });
    if (version === previewVersion && chatId === currentChatId) renderPreview(result.preview);
  } catch (error) {
    if (version === previewVersion) tokenPreview.textContent = error.message;
  }
}

function schedulePreview() {
  ++previewVersion;
  clearTimeout(previewTimer);
  previewTimer = setTimeout(refreshPreview, 250);
}

let currentChatId = null;
let knownChats = [];

function addMessage(role, text) {
  const element = document.createElement("div");
  element.className = `message message--${role}`;
  element.textContent = text;
  messages.appendChild(element);
  messages.scrollTop = messages.scrollHeight;
  return element;
}

function renderMessages(history) {
  messages.replaceChildren();
  if (history.length === 0) {
    addMessage("system", "Это новый чат. Напишите первое сообщение.");
    return;
  }
  for (const message of history) {
    const role = message.role === "assistant" ? "agent" : "user";
    addMessage(role, message.content || "Модель не успела сформировать текст ответа.");
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
  ++previewVersion;
  clearTimeout(previewTimer);
  contextWindow.disabled = isBusy;
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
    const error = new Error(result.error || "Не удалось выполнить запрос");
    error.preview = result.preview;
    throw error;
  }
  return result;
}

async function refreshChatList() {
  const result = await requestJson("/api/chats");
  knownChats = result.chats;
  renderChatList();
}

async function selectChat(chatId) {
  if (chatId !== currentChatId) input.value = "";
  currentChatId = chatId;
  renderChatList();
  setBusy(true);
  try {
    const result = await requestJson(
      `/api/chats/${encodeURIComponent(chatId)}/messages`,
    );
    chatTitle.textContent = result.chat.title;
    renderMessages(result.messages);
    renderStatistics(result.statistics);
    const lastTurn = result.statistics.turns.at(-1);
    if (lastTurn?.warning) addMessage("error", lastTurn.warning);
  } catch (error) {
    addMessage("error", `Ошибка: ${error.message}`);
  } finally {
    setBusy(false);
    input.focus();
    schedulePreview();
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
  if (!message || currentChatId === null || input.disabled) return;

  const windowTokens = Number(contextWindow.value);
  if (!Number.isSafeInteger(windowTokens) || windowTokens <= 0) {
    tokenPreview.textContent = "Размер окна должен быть положительным целым числом.";
    return;
  }
  const draft = input.value;
  if (knownChats.find((chat) => chat.id === currentChatId)?.message_count === 0) {
    messages.replaceChildren();
  }
  const userMessage = addMessage("user", message);
  const pendingAnswer = addMessage("agent", "Модель готовит ответ…");
  pendingAnswer.classList.add("message--pending");
  input.value = "";
  setBusy(true);
  let answerReceived = false;
  const chatUrl = `/api/chats/${encodeURIComponent(currentChatId)}`;
  const requestOptions = {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message, context_window_tokens: windowTokens }),
  };

  try {
    const { preview } = await requestJson(`${chatUrl}/preview`, requestOptions);
    renderPreview(preview);
    const result = await requestJson(`${chatUrl}/messages`, requestOptions);
    answerReceived = true;
    pendingAnswer.classList.remove("message--pending");
    pendingAnswer.textContent = result.answer || "Модель не успела сформировать текст ответа.";
    renderStatistics(result.statistics);
    const lastTurn = result.statistics.turns.at(-1);
    if (lastTurn?.warning) addMessage("error", lastTurn.warning);
    messages.scrollTop = messages.scrollHeight;
    chatTitle.textContent = result.chat.title;
    await refreshChatList();
  } catch (error) {
    if (!answerReceived) {
      userMessage.remove();
      pendingAnswer.remove();
      input.value = draft;
    }
    addMessage("error", answerReceived
      ? `Ответ получен, но не удалось обновить список чатов: ${error.message}`
      : `Ошибка: ${error.message}`);
    if (error.preview) renderPreview(error.preview);
  } finally {
    setBusy(false);
    input.focus();
    schedulePreview();
  }
});

input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    form.requestSubmit();
  }
});

input.addEventListener("input", schedulePreview);
contextWindow.addEventListener("input", schedulePreview);

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
    schedulePreview();
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
