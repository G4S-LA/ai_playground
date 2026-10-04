const elements = {
    chatModel: document.querySelector("#chat-model"),
    embeddingModel: document.querySelector("#embedding-model"),
    chunkCount: document.querySelector("#chunk-count"),
    candidateK: document.querySelector("#candidate-k"),
    finalK: document.querySelector("#final-k"),
    threshold: document.querySelector("#threshold"),
    question: document.querySelector("#question"),
    answer: document.querySelector("#answer"),
    result: document.querySelector("#result"),
    rebuild: document.querySelector("#rebuild"),
    runEvaluation: document.querySelector("#run-evaluation"),
    progress: document.querySelector("#evaluation-progress"),
    progressLabel: document.querySelector("#progress-label"),
    progressValue: document.querySelector("#progress-value"),
    progressBar: document.querySelector("#progress-bar"),
    summary: document.querySelector("#summary"),
    questions: document.querySelector("#questions"),
    toast: document.querySelector("#toast"),
};

let controls = [];

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

function percent(value) { return `${Math.round((value || 0) * 100)}%`; }
function fixed(value) { return Number(value || 0).toFixed(3); }

function retrievalSettings() {
    const settings = {
        candidateK: Number(elements.candidateK.value),
        finalK: Number(elements.finalK.value),
        similarityThreshold: Number(elements.threshold.value),
    };
    if (settings.candidateK < 1 || settings.candidateK > 30) throw new Error("Число кандидатов должно быть от 1 до 30.");
    if (settings.finalK < 1 || settings.finalK > settings.candidateK) throw new Error("Число источников должно быть не больше числа кандидатов.");
    if (settings.similarityThreshold < -1 || settings.similarityThreshold > 1) throw new Error("Порог должен быть от -1 до 1.");
    return settings;
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

function renderSource(source) {
    return `
        <article class="source">
            <header><span class="badge">${escapeHtml(source.citation)}</span><strong>${escapeHtml(source.source)}</strong></header>
            <p>${escapeHtml(source.section)}<br>${escapeHtml(source.chunk_id)}</p>
            <dl>
                <div><dt>Similarity</dt><dd>${fixed(source.similarity)}</dd></div>
                <div><dt>Rerank</dt><dd>${fixed(source.rerankScore)}</dd></div>
            </dl>
        </article>`;
}

function renderQuote(quote) {
    return `
        <article class="quote">
            <header><span class="badge">${escapeHtml(quote.citation)}</span><small>дословно из чанка</small></header>
            <blockquote>${escapeHtml(quote.quote)}</blockquote>
        </article>`;
}

function renderAnswer(result) {
    const unknown = result.needsClarification;
    const validationFailed = result.abstentionReason === "validation_failed";
    const validation = result.validation;
    const errors = validation.errors?.length ? validation.errors.join("\n") : "Ошибок нет.";
    const sourceHtml = result.sources.length ? result.sources.map(renderSource).join("") : "<p>Источники отсутствуют.</p>";
    const quoteHtml = result.quotes.length ? result.quotes.map(renderQuote).join("") : "<p>Цитаты отсутствуют.</p>";
    elements.result.className = `result ${unknown ? "unknown" : "known"}`;
    elements.result.innerHTML = `
        <header class="answer-head">
            <span class="answer-state">${unknown ? "не знаю" : "подтверждено"}</span>
            <h3>${unknown ? (validationFailed ? "Ответ не прошёл проверку" : "Недостаточно контекста") : "Ответ по базе знаний"}</h3>
            <time>${result.elapsedMs} ms · rewrite ${result.rewriteMs} ms</time>
        </header>
        <div class="validation">
            <div><i>${result.sources.length ? "✓" : "—"}</i> Источники: ${result.sources.length}</div>
            <div><i>${result.quotes.length ? "✓" : "—"}</i> Цитаты: ${result.quotes.length}</div>
            <div><i>${validation.valid ? "✓" : "×"}</i> Формат проверен</div>
            <div><i>${validation.valid ? "✓" : "×"}</i> ${validation.valid ? `Попыток: ${validation.attempts}` : "Ответ отклонён"}</div>
        </div>
        <div class="answer-text">${escapeHtml(result.answer)}</div>
        ${unknown ? `<p class="clarification">${escapeHtml(result.clarificationPrompt)}</p>` : `
            <div class="evidence">
                <section><h4>Использованные источники</h4>${sourceHtml}</section>
                <section><h4>Проверенные цитаты</h4>${quoteHtml}</section>
            </div>`}
        <details class="debug">
            <summary>Технические детали</summary>
            <p>Search query: ${escapeHtml(result.searchQuery)}\nКандидатов: ${result.candidates.length}\nОшибки первичного ответа: ${escapeHtml(errors)}</p>
        </details>`;
}

function checkBadge(label, passed) {
    return `<span class="${passed ? "pass" : "fail"}">${passed ? "✓" : "×"} ${label}</span>`;
}

function renderQuestions(results = []) {
    const byId = new Map(results.map(item => [item.control.id, item]));
    elements.questions.innerHTML = controls.map(control => {
        const evaluation = byId.get(control.id);
        const checks = evaluation ? `
            <div class="checks">
                ${checkBadge("источники", evaluation.hasSources)}
                ${checkBadge("цитаты", evaluation.hasQuotes)}
                ${checkBadge("дословность", evaluation.quotesAreExact)}
                ${checkBadge("ссылки", evaluation.citationsAreConsistent)}
                ${checkBadge("смысл", evaluation.semanticSupport.supported)}
            </div>
            <details class="evaluation-details">
                <summary>Ответ и решение судьи</summary>
                <p>${escapeHtml(evaluation.result.answer)}</p>
                <p><b>${escapeHtml(evaluation.semanticSupport.method)}:</b> ${escapeHtml(evaluation.semanticSupport.reason)}</p>
            </details>` : "";
        return `
            <article class="question">
                <b>${String(control.id).padStart(2, "0")}</b>
                <div>
                    <h3>${escapeHtml(control.question)}</h3>
                    <p><b>Ожидание:</b> ${escapeHtml(control.expectation)}</p>
                    <p><b>Источник:</b> ${control.expectedSources.map(escapeHtml).join(", ")}</p>
                    ${checks}
                </div>
                <button data-id="${control.id}">Спросить →</button>
            </article>`;
    }).join("");
    elements.questions.querySelectorAll("button[data-id]").forEach(button => {
        button.addEventListener("click", () => {
            elements.question.value = controls.find(item => item.id === Number(button.dataset.id)).question;
            window.scrollTo({top: document.querySelector(".ask").offsetTop - 20, behavior: "smooth"});
            elements.question.focus();
        });
    });
}

async function ask() {
    const question = elements.question.value.trim();
    if (!question) return showToast("Введите вопрос.", true);
    let settings;
    try { settings = retrievalSettings(); } catch (error) { return showToast(error.message, true); }
    setBusy(elements.answer, true, "Проверяем<br>доказательства…");
    elements.result.className = "result empty";
    elements.result.innerHTML = "<div>Ищем источники и проверяем дословные цитаты…</div>";
    try {
        renderAnswer(await api("/api/answer", {
            method: "POST",
            body: JSON.stringify({question, ...settings}),
        }));
    } catch (error) {
        elements.result.innerHTML = "<div>Ответ не получен.</div>";
        showToast(error.message, true);
    } finally {
        setBusy(elements.answer, false);
    }
}

async function rebuild() {
    setBusy(elements.rebuild, true, "Индексируем…");
    try {
        const result = await api("/api/index", {method: "POST", body: "{}"});
        showToast(`Индекс готов: ${result.chunks} чанков.`);
        await loadInfo();
    } catch (error) {
        showToast(error.message, true);
    } finally {
        setBusy(elements.rebuild, false);
    }
}

function updateProgress(run) {
    elements.progress.classList.remove("hidden");
    elements.progressValue.textContent = `${run.completed} / ${run.total}`;
    elements.progressLabel.textContent = run.currentQuestion || (run.status === "completed" ? "Готово" : "Подготовка…");
    elements.progressBar.style.width = `${run.completed / run.total * 100}%`;
    renderQuestions(run.results);
}

function renderSummary(summary) {
    elements.summary.classList.remove("hidden");
    elements.summary.innerHTML = `
        <div><span>Есть источники</span><strong>${percent(summary.sourcePresenceRate)}</strong></div>
        <div><span>Есть цитаты</span><strong>${percent(summary.quotePresenceRate)}</strong></div>
        <div><span>Цитаты дословны</span><strong>${percent(summary.exactQuoteRate)}</strong></div>
        <div><span>Ссылки согласованы</span><strong>${percent(summary.citationConsistencyRate)}</strong></div>
        <div><span>Смысл подтверждён</span><strong>${percent(summary.semanticSupportRate)}</strong></div>
        <div><span>Recall источников</span><strong>${percent(summary.expectedSourceRecall)}</strong></div>
        <div><span>Ответов «не знаю»</span><strong>${percent(summary.unknownRate)}</strong></div>`;
}

async function runEvaluation() {
    let settings;
    try { settings = retrievalSettings(); } catch (error) { return showToast(error.message, true); }
    setBusy(elements.runEvaluation, true, "Проверка идёт…");
    elements.summary.classList.add("hidden");
    try {
        let run = await api("/api/evaluations", {method: "POST", body: JSON.stringify(settings)});
        updateProgress(run);
        while (run.status === "running") {
            await new Promise(resolve => window.setTimeout(resolve, 900));
            run = await api(`/api/evaluations/${run.id}`);
            updateProgress(run);
        }
        if (run.status === "failed") throw new Error(run.error || "Оценка завершилась с ошибкой.");
        renderSummary(run.summary);
        showToast("Все ответы и доказательства проверены.");
    } catch (error) {
        showToast(error.message, true);
    } finally {
        setBusy(elements.runEvaluation, false);
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

async function initialise() {
    try {
        const [, response] = await Promise.all([loadInfo(), api("/api/questions")]);
        controls = response.questions;
        renderQuestions();
    } catch (error) {
        showToast(error.message, true);
    }
}

elements.answer.addEventListener("click", ask);
elements.rebuild.addEventListener("click", rebuild);
elements.runEvaluation.addEventListener("click", runEvaluation);
elements.question.addEventListener("keydown", event => {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter") ask();
});
initialise();
