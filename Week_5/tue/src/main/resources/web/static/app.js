const elements = {
    chatModel: document.querySelector("#chat-model"),
    embeddingModel: document.querySelector("#embedding-model"),
    chunkCount: document.querySelector("#chunk-count"),
    documentCount: document.querySelector("#document-count"),
    questionCount: document.querySelector("#question-count"),
    question: document.querySelector("#question"),
    topK: document.querySelector("#top-k"),
    compare: document.querySelector("#compare"),
    comparison: document.querySelector("#comparison"),
    rebuild: document.querySelector("#rebuild"),
    runEvaluation: document.querySelector("#run-evaluation"),
    questions: document.querySelector("#questions"),
    progress: document.querySelector("#evaluation-progress"),
    progressLabel: document.querySelector("#progress-label"),
    progressValue: document.querySelector("#progress-value"),
    progressBar: document.querySelector("#progress-bar"),
    summary: document.querySelector("#summary"),
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

function percent(value) {
    return `${Math.round((value || 0) * 100)}%`;
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

function renderQuestions(results = []) {
    const byId = new Map(results.map(item => [item.control.id, item]));
    elements.questions.innerHTML = controls.map(control => {
        const evaluated = byId.get(control.id);
        const score = evaluated ? `
            <div class="score-pair">
                <span>без RAG <b>${percent(evaluated.withoutRag.quality.conceptCoverage)}</b></span>
                <span>с RAG <b>${percent(evaluated.withRag.quality.conceptCoverage)}</b></span>
            </div>` : "";
        const evaluationDetails = evaluated ? `
            <details class="evaluation-details">
                <summary>Сравнить полные ответы</summary>
                <div>
                    <section>
                        <b>Без RAG</b>
                        <p>${escapeHtml(evaluated.withoutRag.result.answer)}</p>
                        <small>Не найдены термины: ${evaluated.withoutRag.quality.missingTerms.map(escapeHtml).join(", ") || "—"}</small>
                    </section>
                    <section>
                        <b>С RAG</b>
                        <p>${escapeHtml(evaluated.withRag.result.answer)}</p>
                        <small>Retrieval: ${evaluated.withRag.result.sources.map(source => escapeHtml(source.source)).join(", ") || "—"}</small>
                    </section>
                </div>
            </details>` : "";
        return `
            <article class="question-card" data-question-id="${control.id}">
                <div class="question-number">${String(control.id).padStart(2, "0")}</div>
                <div class="question-body">
                    <h3>${escapeHtml(control.question)}</h3>
                    <p><span>Ожидание</span>${escapeHtml(control.expectation)}</p>
                    <p><span>Источники</span>${control.expectedSources.map(escapeHtml).join(", ")}</p>
                    <p><span>Разделы</span>${control.expectedSections.map(escapeHtml).join(" · ")}</p>
                    ${score}
                    ${evaluationDetails}
                </div>
                <button class="ask-control" data-id="${control.id}">Спросить →</button>
            </article>`;
    }).join("");

    elements.questions.querySelectorAll(".ask-control").forEach(button => {
        button.addEventListener("click", () => {
            const control = controls.find(item => item.id === Number(button.dataset.id));
            elements.question.value = control.question;
            elements.question.focus();
            window.scrollTo({top: document.querySelector(".ask-panel").offsetTop - 24, behavior: "smooth"});
        });
    });
}

function answerColumn(title, eyebrow, answer, grounded) {
    const sources = grounded && answer.sources.length ? `
        <div class="sources">
            <h4>Retrieval</h4>
            ${answer.sources.map(source => `
                <details>
                    <summary><b>[${escapeHtml(source.citation)}]</b> ${escapeHtml(source.source)} <i>${source.score.toFixed(3)}</i></summary>
                    <p>${escapeHtml(source.section)} · ${escapeHtml(source.chunkId)}</p>
                    <blockquote>${escapeHtml(source.text)}</blockquote>
                </details>`).join("")}
        </div>` : "";
    return `
        <article class="answer ${grounded ? "grounded" : "baseline"}">
            <p class="eyebrow">${eyebrow}</p>
            <div class="answer-heading"><h3>${title}</h3><span>${answer.elapsedMs} ms</span></div>
            <div class="answer-text">${escapeHtml(answer.answer)}</div>
            ${sources}
        </article>`;
}

function renderComparison(comparison) {
    elements.comparison.classList.remove("empty");
    elements.comparison.innerHTML =
        answerColumn("Память модели", "without retrieval", comparison.withoutRag, false) +
        answerColumn("Ответ по базе", "retrieval + context", comparison.withRag, true);
}

async function compare() {
    const question = elements.question.value.trim();
    if (!question) return showToast("Сначала введите вопрос.", true);
    setBusy(elements.compare, true, "Модель<br>отвечает…");
    elements.comparison.className = "comparison empty";
    elements.comparison.innerHTML = '<div class="empty-copy pulse">Ищем чанки и получаем два ответа…</div>';
    try {
        const result = await api("/api/compare", {
            method: "POST",
            body: JSON.stringify({question, topK: Number(elements.topK.value)}),
        });
        renderComparison(result);
    } catch (error) {
        elements.comparison.innerHTML = '<div class="empty-copy">Не удалось получить ответы.</div>';
        showToast(error.message, true);
    } finally {
        setBusy(elements.compare, false);
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

function renderSummary(summary) {
    elements.summary.classList.remove("hidden");
    elements.summary.innerHTML = `
        <div><span>Понятия · без RAG</span><strong>${percent(summary.withoutRagConceptCoverage)}</strong></div>
        <div><span>Понятия · с RAG</span><strong>${percent(summary.withRagConceptCoverage)}</strong></div>
        <div><span>Recall источников</span><strong>${percent(summary.ragSourceRecall)}</strong></div>
        <div><span>Ответы с цитатами</span><strong>${percent(summary.ragCitationRate)}</strong></div>`;
}

function updateProgress(run) {
    elements.progress.classList.remove("hidden");
    elements.progressValue.textContent = `${run.completed} / ${run.total}`;
    elements.progressBar.style.width = `${(run.completed / run.total) * 100}%`;
    elements.progressLabel.textContent = run.currentQuestion || (run.status === "completed" ? "Готово" : "Подготовка…");
    renderQuestions(run.results);
}

async function runEvaluation() {
    setBusy(elements.runEvaluation, true, "Оценка идёт…");
    elements.summary.classList.add("hidden");
    try {
        let run = await api("/api/evaluations", {
            method: "POST",
            body: JSON.stringify({topK: Number(elements.topK.value)}),
        });
        updateProgress(run);
        while (run.status === "running") {
            await new Promise(resolve => window.setTimeout(resolve, 900));
            run = await api(`/api/evaluations/${run.id}`);
            updateProgress(run);
        }
        if (run.status === "failed") throw new Error(run.error || "Оценка завершилась с ошибкой.");
        renderSummary(run.summary);
        showToast("Все 10 вопросов проверены.");
    } catch (error) {
        showToast(error.message, true);
    } finally {
        setBusy(elements.runEvaluation, false);
    }
}

async function loadInfo() {
    const info = await api("/api/info");
    elements.chatModel.textContent = info.chatModel;
    elements.embeddingModel.textContent = `embeddings · ${info.embeddingModel}`;
    elements.questionCount.textContent = info.controlQuestions;
    updateStats(info.stats);
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

elements.compare.addEventListener("click", compare);
elements.rebuild.addEventListener("click", rebuild);
elements.runEvaluation.addEventListener("click", runEvaluation);
elements.question.addEventListener("keydown", event => {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter") compare();
});

initialise();
