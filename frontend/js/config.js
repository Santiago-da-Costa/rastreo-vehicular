(function () {
    const apiBaseUrls = {
        local: "http://localhost:8000",
        render: "https://rastreo-vehicular.onrender.com"
    };

    const hostname = window.location.hostname;
    const isLocalhost =
        hostname === "localhost" ||
        hostname === "127.0.0.1" ||
        hostname.startsWith("192.168.") ||
        hostname.startsWith("10.") ||
        hostname.startsWith("172.");
    const defaultApiBaseUrl = isLocalhost ? apiBaseUrls.local : apiBaseUrls.render;

    const configuredApiBaseUrl = window.RASTREO_API_BASE_URL || defaultApiBaseUrl;
    const apiBaseUrl = configuredApiBaseUrl.trim().replace(/\/+$/, "");

    window.RastreoConfig = {
        apiBaseUrl
    };
})();
