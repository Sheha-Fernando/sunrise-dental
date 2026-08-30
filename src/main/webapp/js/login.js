(function () {
    const form = document.getElementById("loginForm");
    const usernameInput = document.getElementById("username");
    const passwordInput = document.getElementById("password");
    const usernameError = document.getElementById("usernameError");
    const passwordError = document.getElementById("passwordError");
    const loginAlert = document.getElementById("loginAlert");
    const loginButton = document.getElementById("loginButton");
    const togglePassword = document.getElementById("togglePassword");

    if (new URLSearchParams(window.location.search).get("expired") === "1") {
        showAlert("Your session has expired. Please log in again.");
    }

    togglePassword.addEventListener("click", () => {
        const isHidden = passwordInput.type === "password";
        passwordInput.type = isHidden ? "text" : "password";
        togglePassword.textContent = isHidden ? "Hide" : "Show";
        togglePassword.setAttribute("aria-label", isHidden ? "Hide password" : "Show password");
    });

    function showAlert(message) {
        loginAlert.textContent = message;
        loginAlert.classList.add("visible");
    }

    function hideAlert() {
        loginAlert.classList.remove("visible");
        loginAlert.textContent = "";
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
        loginButton.textContent = "Logging in...";

        try {
            await Api.post("/auth/login", {
                username: usernameInput.value.trim(),
                password: passwordInput.value,
            });
            window.location.href = Api.PAGES + "dashboard.html";
        } catch (err) {
            // Deliberately generic - never reveal which field was wrong.
            showAlert(err.message || "Invalid username or password.");
        } finally {
            loginButton.disabled = false;
            loginButton.textContent = "Login";
        }
    });
})();
