import axios from "axios";

const api = axios.create({
<<<<<<< HEAD
    baseURL: import.meta.env.VITE_API_BASE_URL || "http://localhost:8081/api/v1",
=======
    baseURL: import.meta.env.VITE_API_BASE_URL || "http://localhost:8081/api/v1"
>>>>>>> origin/main
});

api.interceptors.request.use((config) => {
    const token = localStorage.getItem("token");

    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }

    return config;
});

export default api;