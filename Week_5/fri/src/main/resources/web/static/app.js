const elements = {
    chatModel: document.querySelector("#chat-model"),
    embeddingModel: document.querySelector("#embedding-model"),
    chunkCount: document.querySelector("#chunk-count"),
    turnCount: document.querySelector("#turn-count"),
    newChat: document.querySelector("#new-chat"),
    rebuild: document.querySelector("#rebuild"),
    messages: document.querySelector("#messages"),
    composer: document.querySelector("#composer"),
    message: document.querySelector("#message"),
    send: document.querySelector("#send"),
    candidateK: document.querySelector("#candidate-k"),
    finalK: document.querySelector("#final-k"),
    threshold: document.querySelector("#threshold"),
    memoryGoal: document.querySelector("#memory-goal"),
    memoryFacts: document.querySelector("#memory-facts"),
    memoryConstraints: document.querySelector("#memory-constraints"),
    memoryTerms: document.querySelector("#memory-terms"),
    memoryRevision: document.querySelector("#memory-revision"),
    memoryMethod: document.querySelector("#memory-method"),
    toast: document.querySelector("#toast"),
};

const SESSION_KEY = "rag-memory-chat-session";
let session = null;

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>'"]/g, char => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"})[char]);
}

async function api(path, options = {}) {
    const response = await fetch(path, {headers: {"Content-Type": "application/json"}, ...options});
    if (response.status === 204) return null;
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
    return payload;
}

function toast(message, error = false) {
    elements.toast.textContent = message;
    elements.toast.className = `toast visible${error ? " error" : ""}`;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => elements.toast.className = "toast", 3200);
}

function setBusy(button, busy, text) {
    if (!button.dataset.label) button.dataset.label = button.textContent;
    button.disabled = busy;
    button.textContent = busy ? text : button.dataset.label;
}

function listHtml(items, render = escapeHtml) {
    if (!items?.length) return {html: "Пока ничего", empty: true};
    return {html: items.map(item => `<span>${render(item)}</span>`).join(""), empty: false};
}

function renderMemory(memory) {
    elements.memoryGoal.textContent = memory.goal || "Цель появится после первого сообщения.";
    elements.memoryRevision.textContent = `v${memory.revision}`;
    elements.memoryMethod.textContent = memory.updateMethod;
    const facts = listHtml(memory.clarifiedFacts);
    const constraints = listHtml(memory.constraints);
    const terms = listHtml(memory.terms, term => `<b>${escapeHtml(term.term)}</b>${escapeHtml(term.meaning)}`);
    [[elements.memoryFacts, facts], [elements.memoryConstraints, constraints], [elements.memoryTerms, terms]].forEach(([element, value]) => {
        element.innerHTML = value.html;
        element.classList.toggle("empty", value.empty);
    });
}

function evidenceHtml(message) {
    const sources = message.sources || [];
    const quotes = message.quotes || [];
    const sourceItems = sources.length ? sources.map(source => `
        <article class="source">
            <header><span class="badge">${escapeHtml(source.citation)}</span><strong>${escapeHtml(source.source)}</strong></header>
            <p>${escapeHtml(source.section)} · ${escapeHtml(source.chunk_id)}</p>
        </article>`).join("") : '<div class="source-empty">Релевантные источники не найдены.</div>';
    const quoteItems = quotes.length ? quotes.map(quote => `
        <article class="quote">
            <span class="badge">${escapeHtml(quote.citation)}</span>
            <blockquote>${escapeHtml(quote.quote)}</blockquote>
        </article>`).join("") : '<div class="source-empty">Для ответа «не знаю» цитаты отсутствуют.</div>';
    return `<details class="evidence" ${sources.length ? "open" : ""}>
        <summary>Источники · ${sources.length} / Цитаты · ${quotes.length}</summary>
        <div class="evidence-body">
            <section><h4>Sources</h4>${sourceItems}</section>
            <section><h4>Exact quotes</h4>${quoteItems}</section>
        </div>
    </details>`;
}

function renderMessages(messages) {
    if (!messages.length) {
        elements.messages.innerHTML = `<div class="welcome"><b>Начните диалог</b><p>Сформулируйте цель, затем уточняйте условия короткими репликами. Память справа не даст задаче потеряться.</p></div>`;
        return;
    }
    elements.messages.innerHTML = messages.map(message => {
        const assistant = message.role === "assistant";
        return `<article class="message ${assistant ? "assistant" : "user"}${message.needsClarification ? " unknown" : ""}">
            <div class="message-label">${assistant ? "Assistant" : "You"}</div>
            <div class="bubble">${escapeHtml(message.content)}</div>
            ${assistant ? evidenceHtml(message) : ""}
        </article>`;
    }).join("");
    elements.messages.scrollTop = elements.messages.scrollHeight;
}

function renderSession(value) {
    session = value;
    localStorage.setItem(SESSION_KEY, session.id);
    elements.turnCount.textContent = session.messages.length;
    renderMemory(session.memory);
    renderMessages(session.messages);
}

function retrievalSettings() {
    const candidateK = Number(elements.candidateK.value);
    const finalK = Number(elements.finalK.value);
    const similarityThreshold = Number(elements.threshold.value);
    if (!Number.isInteger(candidateK) || candidateK < 1 || candidateK > 30) throw new Error("Кандидаты: от 1 до 30.");
    if (!Number.isInteger(finalK) || finalK < 1 || finalK > candidateK) throw new Error("В контекст: от 1 до числа кандидатов.");
    if (!Number.isFinite(similarityThreshold) || similarityThreshold < -1 || similarityThreshold > 1) throw new Error("Порог: от -1 до 1.");
    return {candidateK, finalK, similarityThreshold};
}

async function createChat(removeCurrent = false) {
    if (removeCurrent && session) await api(`/api/chats/${encodeURIComponent(session.id)}`, {method: "DELETE"}).catch(() => null);
    renderSession(await api("/api/chats", {method: "POST", body: "{}"}));
    elements.message.focus();
}

async function restoreChat() {
    const id = localStorage.getItem(SESSION_KEY);
    if (!id) return createChat();
    try { renderSession(await api(`/api/chats/${encodeURIComponent(id)}`)); }
    catch (_) { await createChat(); }
}

async function sendMessage() {
    const message = elements.message.value.trim();
    if (!message) return;
    let settings;
    try { settings = retrievalSettings(); } catch (error) { return toast(error.message, true); }
    if (!session) await createChat();
    setBusy(elements.send, true, "Думаю…");
    elements.message.disabled = true;
    try {
        const reply = await api(`/api/chats/${encodeURIComponent(session.id)}/messages`, {
            method: "POST",
            body: JSON.stringify({message, ...settings}),
        });
        elements.message.value = "";
        renderSession(reply.session);
    } catch (error) {
        toast(error.message, true);
    } finally {
        setBusy(elements.send, false);
        elements.message.disabled = false;
        elements.message.focus();
    }
}

async function loadInfo() {
    const info = await api("/api/info");
    elements.chatModel.textContent = info.chatModel;
    elements.embeddingModel.textContent = info.embeddingModel;
    const structured = info.stats.find(item => item.strategy === "structured");
    elements.chunkCount.textContent = structured?.chunks ?? 0;
    elements.candidateK.value = info.defaults.candidateK;
    elements.finalK.value = info.defaults.finalK;
    elements.threshold.value = info.defaults.similarityThreshold;
}

elements.composer.addEventListener("submit", event => { event.preventDefault(); sendMessage(); });
elements.message.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); sendMessage(); }
});
elements.newChat.addEventListener("click", async () => {
    setBusy(elements.newChat, true, "Создаю…");
    try { await createChat(true); } catch (error) { toast(error.message, true); }
    finally { setBusy(elements.newChat, false); }
});
elements.rebuild.addEventListener("click", async () => {
    setBusy(elements.rebuild, true, "Индексирую…");
    try { const result = await api("/api/index", {method: "POST", body: "{}"}); toast(`Индекс готов: ${result.chunks} чанков.`); await loadInfo(); }
    catch (error) { toast(error.message, true); }
    finally { setBusy(elements.rebuild, false); }
});

Promise.all([loadInfo(), restoreChat()]).catch(error => toast(error.message, true));
