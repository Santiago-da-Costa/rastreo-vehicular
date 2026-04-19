(function () {
    const tokenKey = "rastreo_vehicular_token";
    const userKey = "rastreo_vehicular_user";
    const apiBaseUrl = window.location.origin;

    function getToken() {
        return window.localStorage.getItem(tokenKey);
    }

    function saveToken(token) {
        window.localStorage.setItem(tokenKey, token);
    }

    function clearSession() {
        window.localStorage.removeItem(tokenKey);
        window.localStorage.removeItem(userKey);
    }

    function saveCurrentUser(user) {
        window.localStorage.setItem(userKey, JSON.stringify(user));
    }

    function getCurrentUser() {
        const rawUser = window.localStorage.getItem(userKey);
        if (!rawUser) {
            return null;
        }

        try {
            return JSON.parse(rawUser);
        } catch (error) {
            return null;
        }
    }

    function getLoginUrl() {
        const loginUrl = new URL("login.html", window.location.href);
        const currentPage = `${window.location.pathname}${window.location.search}`;
        if (!window.location.pathname.endsWith("/login.html")) {
            loginUrl.searchParams.set("next", currentPage);
        }
        return loginUrl.toString();
    }

    function redirectToLogin() {
        window.location.href = getLoginUrl();
    }

    function getRedirectAfterLogin() {
        const params = new URLSearchParams(window.location.search);
        return params.get("next") || "trip_map.html";
    }

    function buildHeaders(options = {}) {
        const headers = new Headers(options.headers || {});
        const token = getToken();

        if (token) {
            headers.set("Authorization", `Bearer ${token}`);
        }

        return headers;
    }

    async function authFetch(url, options = {}) {
        const response = await fetch(url, {
            ...options,
            headers: buildHeaders(options)
        });

        if (response.status === 401) {
            clearSession();
            redirectToLogin();
            throw new Error("Sesión expirada");
        }

        return response;
    }

    async function login(username, password) {
        const response = await fetch(`${apiBaseUrl}/auth/login`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ username, password })
        });

        if (!response.ok) {
            let detail = `Error ${response.status}`;
            try {
                const body = await response.json();
                detail = body.detail || detail;
            } catch (error) {
                // Keep the HTTP status when the backend does not return JSON.
            }
            throw new Error(detail);
        }

        const tokenResponse = await response.json();
        saveToken(tokenResponse.access_token);
        return loadCurrentUser();
    }

    async function loadCurrentUser() {
        const response = await authFetch(`${apiBaseUrl}/auth/me`);

        if (!response.ok) {
            throw new Error(`Error ${response.status}`);
        }

        const user = await response.json();
        saveCurrentUser(user);
        return user;
    }

    async function requireAuth() {
        if (!getToken()) {
            redirectToLogin();
            return null;
        }

        try {
            return await loadCurrentUser();
        } catch (error) {
            clearSession();
            redirectToLogin();
            return null;
        }
    }

    function logout() {
        clearSession();
        redirectToLogin();
    }

    function hasPermission(permissionName) {
        const user = getCurrentUser();
        return Boolean(user && user.permissions && user.permissions[permissionName]);
    }

    window.RastreoAuth = {
        authFetch,
        clearSession,
        getCurrentUser,
        getRedirectAfterLogin,
        getToken,
        hasPermission,
        loadCurrentUser,
        login,
        logout,
        requireAuth
    };
})();
