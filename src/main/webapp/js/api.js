/**
 * Sunrise Dental - shared API helper.
 * Every page loads this before its own page script. Wraps fetch() so that
 * JSON parsing, HTTP errors, auth (401) handling and network failures are
 * handled in exactly one place instead of being repeated per page.
 */
const Api = (() => {

    function contextPath() {
        // "/sunrise-dental/pages/dashboard.html" -> "/sunrise-dental"
        // "/sunrise-dental/index.html"           -> "/sunrise-dental"
        const segments = window.location.pathname.split("/").filter(Boolean);
        return segments.length > 0 ? "/" + segments[0] : "";
    }

    const ROOT = contextPath() + "/";
    const PAGES = contextPath() + "/pages/";
    const API_BASE = contextPath() + "/api";

    class ApiError extends Error {
        constructor(message, status, data) {
            super(message);
            this.status = status;
            this.data = data;
        }
    }

    async function request(path, options = {}) {
        const isLoginCall = path.startsWith("/auth/login");
        let response;
        try {
            response = await fetch(API_BASE + path, {
                credentials: "same-origin",
                ...options,
            });
        } catch (networkError) {
            throw new ApiError("We couldn't reach the server. Please check your connection and try again.", 0, null);
        }

        let data = null;
        const text = await response.text();
        if (text) {
            try {
                data = JSON.parse(text);
            } catch (parseError) {
                data = null;
            }
        }

        if (response.status === 401 && !isLoginCall) {
            window.location.href = ROOT + "index.html?expired=1";
            throw new ApiError("Your session has expired. Please log in again.", 401, data);
        }

        if (!response.ok) {
            const message = (data && data.message) || "We couldn't complete the request. Please try again.";
            throw new ApiError(message, response.status, data);
        }

        return data;
    }

    function get(path) {
        return request(path, { method: "GET" });
    }

    function post(path, formFields) {
        const body = new URLSearchParams();
        Object.entries(formFields || {}).forEach(([key, value]) => {
            if (value !== null && value !== undefined && value !== "") {
                body.append(key, value);
            }
        });
        return request(path, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: body.toString(),
        });
    }

    return { get, post, ApiError, ROOT, PAGES };
})();
