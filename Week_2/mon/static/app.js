const form = document.querySelector("#chat-form");
const input = document.querySelector("#message-input");
const messages = document.querySelector("#messages");
const sendButton = document.querySelector("#send-button");
const resetButton = document.querySelector("#reset-button");

function addMessage(role, text) {
  const element = document.createElement("div");
  element.className = `message message--${role}`;
  element.textContent = text;
  messages.appendChild(element);
  messages.scrollTop = messages.scrollHeight;
}

function setBusy(isBusy) {
  input.disabled = isBusy;
  sendButton.disabled = isBusy;
  resetButton.disabled = isBusy;
  sendButton.textContent = isBusy ? "Ждём ответ..." : "Отправить";
}

async function postJson(url, body = {}) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const result = await response.json();
  if (!response.ok) {
    throw new Error(result.error || "Не удалось выполнить запрос");
  }
  return result;
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const message = input.value.trim();
  if (!message) return;

  addMessage("user", message);
  input.value = "";
  setBusy(true);

  try {
    const result = await postJson("/api/chat", { message });
    addMessage("agent", result.answer);
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

resetButton.addEventListener("click", async () => {
  setBusy(true);
  try {
    await postJson("/api/reset");
    messages.replaceChildren();
    addMessage("system", "История диалога очищена.");
  } catch (error) {
    addMessage("error", `Ошибка: ${error.message}`);
  } finally {
    setBusy(false);
    input.focus();
  }
});

input.focus();
