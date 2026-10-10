const elements = {
    modelCard: document.querySelector("#model-card"),
    runtimeState: document.querySelector("#runtime-state"),
    chatModel: document.querySelector("#chat-model"),
    embeddingModel: document.querySelector("#embedding-model"),
    flowChat: document.querySelector("#flow-chat"),
    flowEmbedding: document.querySelector("#flow-embedding"),
    chunkCount: document.querySelector("#chunk-count"),
    documentCount: document.querySelector("#document-count"),
    modelCount: document.querySelector("#model-count"),
    question: document.querySelector("#question"),
    topK: document.querySelector("#top-k"),
    ask: document.querySelector("#ask"),
    comparison: document.querySelector("#comparison"),
    rebuild: document.querySelector("#rebuild"),
    toast: document.querySelector("#toast"),
};

async function api(path, options = {}) {
    const response = await fetch(path, {
        headers: {"Content-Type": "application/json", ...(options.headers || {})},
        ...options,
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
    return body;
}

function escapeHtml(value = "") {
    return String(value).replace(/[&<>'"]/g, char => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
    })[char]);
}

function showToast(message, error = false) {
    elements.toast.textContent = message;
    elements.toast.classList.toggle("error", error);
    elements.toast.classList.add("visible");
    window.setTimeout(() => elements.toast.classList.remove("visible"), 3500);
}

function setBusy(button, busy, label) {
    if (!button.dataset.label) button.dataset.label = button.innerHTML;
    button.disabled = busy;
    button.innerHTML = busy ? label : button.dataset.label;
}

function updateStats(stats) {
    const structured = stats.find(item => item.strategy === "structured");
    elements.chunkCount.textContent = structured?.chunks ?? 0;
    elements.documentCount.textContent = structured?.documents ?? 0;
}

function sourceList(sources) {
    if (!sources.length) return '<div class="empty-copy">Retrieval не вернул источников.</div>';
    return `
        <div class="sources source-list">
            <h4>Retrieval</h4>
            ${sources.map(source => `
                <details>
                    <summary><b>[${escapeHtml(source.citation)}]</b> ${escapeHtml(source.source)} <i>${Number(source.score).toFixed(3)}</i></summary>
                    <p>${escapeHtml(source.section)} · ${escapeHtml(source.chunkId)}</p>
                    <blockquote>${escapeHtml(source.text)}</blockquote>
                </details>`).join("")}
        </div>`;
}

function renderAnswer(result) {
    elements.comparison.className = "comparison";
    elements.comparison.innerHTML = `
        <article class="answer grounded">
            <p class="eyebrow">local generation</p>
            <div class="answer-heading">
                <h3>Ответ по базе</h3>
                <span>${result.totalMs} ms</span>
            </div>
            <div class="answer-text">${escapeHtml(result.answer)}</div>
            <div class="score-pair timings">
                <span>retrieval <b>${result.retrievalMs} ms</b></span>
                <span>generation <b>${result.generationMs} ms</b></span>
            </div>
        </article>
        <article class="answer evidence">
            <p class="eyebrow">local retrieval</p>
            <div class="answer-heading">
                <h3>Доказательства</h3>
                <span>${result.sources.length} sources</span>
            </div>
            ${sourceList(result.sources)}
        </article>`;
}

async function ask() {
    const question = elements.question.value.trim();
    if (!question) return showToast("Сначала введите вопрос.", true);
    setBusy(elements.ask, true, "Retrieval +<br>generation…");
    elements.comparison.className = "comparison empty";
    elements.comparison.innerHTML = '<div class="empty-copy pulse">Ищем чанки и получаем ответ локальной модели…</div>';
    try {
        const result = await api("/api/ask", {
            method: "POST",
            body: JSON.stringify({question, topK: Number(elements.topK.value)}),
        });
        renderAnswer(result);
    } catch (error) {
        elements.comparison.innerHTML = '<div class="empty-copy">Не удалось получить локальный RAG-ответ.</div>';
        showToast(error.message, true);
    } finally {
        setBusy(elements.ask, false);
    }
}

async function rebuild() {
    setBusy(elements.rebuild, true, "Индексируем…");
    try {
        const result = await api("/api/index", {method: "POST", body: "{}"});
        showToast(`Готово: ${result.chunks} чанков из ${result.documents} документов.`);
        await loadInfo();
    } catch (error) {
        showToast(error.message, true);
    } finally {
        setBusy(elements.rebuild, false);
    }
}

async function loadInfo() {
    const info = await api("/api/info");
    elements.chatModel.textContent = info.chatModel;
    elements.embeddingModel.textContent = `embeddings · ${info.embeddingModel}`;
    elements.flowChat.textContent = info.chatModel;
    elements.flowEmbedding.textContent = info.embeddingModel;
    updateStats(info.stats);

    const installed = Number(info.chatModelInstalled) + Number(info.embeddingModelInstalled);
    elements.modelCount.textContent = `${installed}/2`;
    elements.modelCard.classList.toggle("offline", !info.status.reachable);
    elements.modelCard.classList.toggle("warning", info.status.reachable && installed < 2);
    if (!info.status.reachable) {
        elements.runtimeState.textContent = "Runtime · Ollama недоступна";
    } else if (installed < 2) {
        elements.runtimeState.textContent = "Runtime · установите обе модели";
    } else {
        elements.runtimeState.textContent = "Runtime · fully local";
    }
}

async function initialise() {
    try {
        await loadInfo();
    } catch (error) {
        elements.modelCard.classList.add("offline");
        elements.runtimeState.textContent = "Runtime · ошибка проверки";
        showToast(error.message, true);
    }
}

elements.ask.addEventListener("click", ask);
elements.rebuild.addEventListener("click", rebuild);
elements.question.addEventListener("keydown", event => {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter") ask();
});

initialise();
