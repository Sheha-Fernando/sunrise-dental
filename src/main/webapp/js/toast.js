/**
 * Restrained toast notifications for short-lived success/error events.
 * Form-level validation errors should still use inline .alert elements,
 * not toasts - see the design guidance in each page's script.
 */
const Toast = (() => {
    let stack = null;

    function ensureStack() {
        if (!stack) {
            stack = document.createElement("div");
            stack.className = "toast-stack";
            document.body.appendChild(stack);
        }
        return stack;
    }

    function show(message, type = "default", durationMs = 3500) {
        const container = ensureStack();
        const el = document.createElement("div");
        el.className = "toast" + (type === "error" ? " toast-error" : type === "success" ? " toast-success" : "");
        el.textContent = message;
        container.appendChild(el);
        requestAnimationFrame(() => el.classList.add("visible"));
        setTimeout(() => {
            el.classList.remove("visible");
            setTimeout(() => el.remove(), 200);
        }, durationMs);
    }

    return {
        success: (message) => show(message, "success"),
        error: (message) => show(message, "error"),
        info: (message) => show(message, "default"),
    };
})();
