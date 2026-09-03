(function () {
    const form = document.getElementById("loginForm");
    const usernameInput = document.getElementById("username");
    const passwordInput = document.getElementById("password");
    const usernameError = document.getElementById("usernameError");
    const passwordError = document.getElementById("passwordError");
    const loginAlert = document.getElementById("loginAlert");
    const loginButton = document.getElementById("loginButton");
    const togglePassword = document.getElementById("togglePassword");
    const forgotPasswordLink = document.getElementById("forgotPasswordLink");

    const MIN_LOADING_MS = 800;

    if (new URLSearchParams(window.location.search).get("expired") === "1") {
        showAlert("Your session has expired. Please log in again.");
    }

    togglePassword.addEventListener("click", () => {
        const isHidden = passwordInput.type === "password";
        passwordInput.type = isHidden ? "text" : "password";
        togglePassword.textContent = isHidden ? "Hide" : "Show";
        togglePassword.setAttribute("aria-label", isHidden ? "Hide password" : "Show password");
    });

    forgotPasswordLink.addEventListener("click", () => {
        loginAlert.classList.remove("alert-error");
        loginAlert.classList.add("alert-info");
        showAlert("Please contact your administrator to reset your password.");
    });

    [usernameInput, passwordInput].forEach((input) => {
        input.addEventListener("input", () => {
            input.removeAttribute("aria-invalid");
            hideAlert();
        });
    });

    function showAlert(message) {
        loginAlert.textContent = message;
        loginAlert.classList.add("visible");
    }

    function hideAlert() {
        loginAlert.classList.remove("visible", "alert-info");
        loginAlert.classList.add("alert-error");
        loginAlert.textContent = "";
    }

    function wait(ms) {
        return new Promise((resolve) => setTimeout(resolve, ms));
    }

    function setFieldError(input, errorEl, message) {
        if (message) {
            input.setAttribute("aria-invalid", "true");
            errorEl.textContent = message;
            errorEl.classList.add("visible");
        } else {
            input.removeAttribute("aria-invalid");
            errorEl.classList.remove("visible");
        }
    }

    function validate() {
        let valid = true;
        if (!usernameInput.value.trim()) {
            setFieldError(usernameInput, usernameError, "Username is required.");
            valid = false;
        } else {
            setFieldError(usernameInput, usernameError, null);
        }
        if (!passwordInput.value) {
            setFieldError(passwordInput, passwordError, "Password is required.");
            valid = false;
        } else {
            setFieldError(passwordInput, passwordError, null);
        }
        return valid;
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        hideAlert();

        if (!validate()) {
            return;
        }

        loginButton.disabled = true;
        loginButton.textContent = "Signing in…";

        const loginRequest = Api.post("/auth/login", {
            username: usernameInput.value.trim(),
            password: passwordInput.value,
        });
        // Always show the loading state for at least MIN_LOADING_MS, win or
        // lose - allSettled (not all/race) so a fast rejection can't cut the
        // minimum duration short.
        const [outcome] = await Promise.allSettled([loginRequest, wait(MIN_LOADING_MS)]);

        if (outcome.status === "fulfilled") {
            window.location.href = Api.PAGES + "dashboard.html";
            return;
        }

        // Deliberately generic - never reveal which field was wrong.
        const err = outcome.reason;
        usernameInput.setAttribute("aria-invalid", "true");
        passwordInput.setAttribute("aria-invalid", "true");
        showAlert((err && err.message) || "Incorrect username or password.");
        loginButton.disabled = false;
        loginButton.textContent = "Sign in";
    });
})();
