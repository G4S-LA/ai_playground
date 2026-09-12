const chatList = document.querySelector("#chat-list");
const chatTitle = document.querySelector("#chat-title");
const messages = document.querySelector("#messages");
const form = document.querySelector("#chat-form");
const input = document.querySelector("#message-input");
const sendButton = document.querySelector("#send-button");
const newChatButton = document.querySelector("#new-chat-button");
const deleteButton = document.querySelector("#delete-button");
const contextWindow = document.querySelector("#context-window");
const compressionEnabled = document.querySelector("#compression-enabled");
const keepRecent = document.querySelector("#keep-recent");
const summaryEvery = document.querySelector("#summary-every");
const summaryMaxTokens = document.querySelector("#summary-max-tokens");
const compressionStatus = document.querySelector("#compression-status");
const historySummary = document.querySelector("#history-summary");
const compressionUsage = document.querySelector("#compression-usage");
const tokenPreview = document.querySelector("#token-preview");
const contextProgress = document.querySelector("#context-progress");
const usageTotals = document.querySelector("#usage-totals");
const usageRows = document.querySelector("#usage-rows");
const lastRequestTokens = document.querySelector("#last-request-tokens");
const lastAnswerTokens = document.querySelector("#last-answer-tokens");
const contextBreakdown = document.querySelector("#context-breakdown");
const tokenDetails = document.querySelector("#token-details");
const contextOverflowMessage = "Переполнение контекста: обязательная часть запроса не помещается. Увеличьте лимит, сократите вопрос или число свежих сообщений.";
const number = new Intl.NumberFormat("ru-RU");
let previewTimer;
let previewVersion = 0;

function money(value) {
  return value === null ? "—" : value.toFixed(6);
}

function renderStatistics(stats) {
  const total = stats.overall_totals;
  const summaryTotal = stats.summary_totals;
  compressionEnabled.checked = stats.compression.enabled;
  keepRecent.value = stats.compression.keep_recent_messages;
  summaryEvery.value = stats.compression.summary_every_messages;
  summaryMaxTokens.value = stats.compression.summary_max_tokens;
  renderCompression(stats.compression);
  compressionUsage.textContent = `Сжатие: ${summaryTotal.turn_count} запросов, ` +
    `${summaryTotal.has_estimates ? "≈" : ""}${number.format(summaryTotal.total_tokens)} токенов; ` +
    `известная стоимость $${money(summaryTotal.known_cost_usd)}; без цены: ${summaryTotal.unpriced_turns}. ` +
    "Включено в расход всего диалога. Таблица ниже — только ответы пользователю.";
  contextWindow.value = stats.context_window_tokens;
  const lastTurn = stats.turns.at(-1);
  lastRequestTokens.textContent = "Последний запрос в токенах: " + (lastTurn
    ? `${lastTurn.input_source === "api" ? "" : "≈"}${number.format(lastTurn.input_tokens)}` : "—");
  lastAnswerTokens.textContent = "Последний ответ в токенах: " + (lastTurn
    ? `${lastTurn.output_source === "api" ? "" : "≈"}${number.format(lastTurn.output_tokens)}` : "—");
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
      turn.omitted_history_messages ?? "—",
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

function renderCompression(state) {
  historySummary.textContent = state.summary || "Summary ещё не создано.";
  compressionStatus.textContent = state.enabled
    ? `В summary: ${state.summarized_messages} сообщ. (≈${state.summary_tokens_estimate} токенов). ` +
      `Дословно: ${state.verbatim_messages} сообщ.` +
      (state.pending_messages ? ` Перед ответом сожмём ещё ${state.pending_messages} сообщ.` : "")
    : "Сжатие выключено. Используется история в пределах окна; сохранённое summary не отправляется.";
}

function compressionSettings() {
  const keep = Number(keepRecent.value);
  const every = Number(summaryEvery.value);
  const maxTokens = Number(summaryMaxTokens.value);
  if (!Number.isSafeInteger(maxTokens) || maxTokens <= 0) {
    throw new Error("Лимит summary должен быть положительным целым числом.");
  }
  if (![keep, every].every((value) => Number.isSafeInteger(value) && value >= 2 && value % 2 === 0)) {
    throw new Error("Число свежих сообщений и интервал сжатия должны быть чётными числами от 2.");
  }
  return {
    enabled: compressionEnabled.checked, keep_recent_messages: keep,
    summary_every_messages: every, summary_max_tokens: maxTokens,
  };
}

function renderPreview(preview) {
  renderCompression(preview.compression);
  const pending = preview.compression.pending_messages > 0;
  tokenPreview.textContent = `Контекст: ≈${number.format(preview.input_tokens_estimate)} ` +
    `из ${number.format(preview.context_window_tokens)} токенов` + (pending ? " · до обновления summary" : "");
  const omitted = preview.omitted_history_messages || 0;
  const overflow = !preview.fits && !pending;
  contextBreakdown.hidden = !pending && preview.fits && omitted === 0;
  contextBreakdown.textContent = pending
    ? "Перед отправкой агент обновит summary и пересчитает контекст. Сжатие — дополнительный запрос к модели."
    : overflow ? contextOverflowMessage :
    (omitted ? `Старых сообщений вне контекста: ${number.format(omitted)}. ` +
      "Они сохранены в чате, но модель их не увидит." : "");
  tokenPreview.classList.toggle("is-overflow", overflow);
  contextBreakdown.classList.toggle("is-overflow", overflow);
  contextProgress.classList.toggle("is-overflow", overflow);
  contextProgress.max = preview.context_window_tokens;
  contextProgress.value = Math.min(preview.input_tokens_estimate, preview.context_window_tokens);
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
      body: JSON.stringify({ message: input.value, context_window_tokens: tokens, compression: compressionSettings() }),
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
  compressionEnabled.disabled = isBusy;
  keepRecent.disabled = isBusy;
  summaryEvery.disabled = isBusy;
  summaryMaxTokens.disabled = isBusy;
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
    const error = new Error(result.code === "context_overflow"
      ? contextOverflowMessage : result.error || "Не удалось выполнить запрос");
    error.preview = result.preview;
    error.statistics = result.statistics;
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
  let compression;
  try {
    compression = compressionSettings();
  } catch (error) {
    tokenPreview.textContent = error.message;
    return;
  }
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
    body: JSON.stringify({ message, context_window_tokens: windowTokens, compression }),
  };

  try {
    const { preview } = await requestJson(`${chatUrl}/preview`, requestOptions);
    renderPreview(preview);
    if (preview.compression.pending_messages) pendingAnswer.textContent = "Модель сжимает историю и готовит ответ…";
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
    if (error.statistics) renderStatistics(error.statistics);
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
compressionEnabled.addEventListener("change", schedulePreview);
keepRecent.addEventListener("input", schedulePreview);
summaryEvery.addEventListener("input", schedulePreview);
summaryMaxTokens.addEventListener("input", schedulePreview);

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
