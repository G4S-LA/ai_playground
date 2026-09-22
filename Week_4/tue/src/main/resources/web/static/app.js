const messages = document.querySelector("#messages");
const form = document.querySelector("#chat-form");
const input = document.querySelector("#message");
const send = document.querySelector("#send");
const deleteSession = document.querySelector("#delete-session");
const newSession = document.querySelector("#new-session");
const sessionList = document.querySelector("#session-list");
const toolList = document.querySelector("#tool-list");

let currentSessionId = null;

function addMessage(role, text, tools = []) {
  const article = document.createElement("article");
  article.className = `message message--${role}`;
  const label = document.createElement("span");
  label.textContent = role === "user" ? "Вы" : "Агент";
  const body = document.createElement("p");
  body.textContent = text;
  article.append(label, body);
  tools.forEach((tool) => {
    const badge = document.createElement("small");
    badge.textContent = `MCP вызов: ${tool.name}`;
    article.append(badge);
  });
  messages.append(article);
  messages.scrollTop = messages.scrollHeight;
}

function renderMessages(entries) {
  messages.replaceChildren();
  if (!entries.length) {
    addMessage("assistant", "Спросите о состоянии репозитория или последних коммитах.");
    return;
  }
  entries.forEach((entry) => addMessage(entry.role, entry.content));
}

async function request(url, options) {
  const response = await fetch(url, options);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
  return payload;
}

async function selectSession(id) {
  const payload = await request(`/api/sessions/${id}`);
  currentSessionId = id;
  renderMessages(payload.snapshot.session.messages);
  document.querySelectorAll(".session-button").forEach((button) => {
    button.classList.toggle("active", button.dataset.id === id);
  });
}

async function loadSessions(preferredId) {
  let payload = await request("/api/sessions");
  if (!payload.sessions.length) {
    const created = await request("/api/sessions", { method: "POST" });
    payload = { sessions: [created.session] };
  }
  sessionList.replaceChildren(...payload.sessions.map((session) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "session-button";
    button.dataset.id = session.id;
    button.textContent = session.title;
    button.addEventListener("click", () => selectSession(session.id));
    return button;
  }));
  const target = preferredId && payload.sessions.some((item) => item.id === preferredId)
    ? preferredId
    : payload.sessions[0].id;
  await selectSession(target);
}

async function loadTools() {
  try {
    const payload = await request("/api/tools");
    toolList.replaceChildren(...payload.tools.map((tool) => {
      const card = document.createElement("article");
      const title = document.createElement("h3");
      title.textContent = tool.name;
      const description = document.createElement("p");
      description.textContent = tool.description;
      card.append(title, description);
      return card;
    }));
  } catch (error) {
    toolList.textContent = error.message;
  }
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const message = input.value.trim();
  if (!message || !currentSessionId) return;
  addMessage("user", message);
  input.value = "";
  send.disabled = true;
  try {
    const payload = await request(`/api/sessions/${currentSessionId}/messages`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message }),
    });
    addMessage("assistant", payload.answer, payload.toolExecutions);
    await loadSessions(currentSessionId);
  } catch (error) {
    addMessage("assistant", `Ошибка: ${error.message}`);
  } finally {
    send.disabled = false;
    input.focus();
  }
});

newSession.addEventListener("click", async () => {
  const payload = await request("/api/sessions", { method: "POST" });
  await loadSessions(payload.session.id);
});

deleteSession.addEventListener("click", async () => {
  if (!currentSessionId) return;
  await request(`/api/sessions/${currentSessionId}`, { method: "DELETE" });
  currentSessionId = null;
  await loadSessions();
});

input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    form.requestSubmit();
  }
});

Promise.all([loadSessions(), loadTools()]).catch((error) => {
  addMessage("assistant", `Ошибка запуска: ${error.message}`);
});
