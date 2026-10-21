const BASE_URL =
  import.meta.env.VITE_RATE_SERVICE_URL ||
  "http://localhost:8081";

function createBasicAuth(username, password) {
  return "Basic " + btoa(`${username}:${password}`);
}

export async function apiRequest(
  path,
  {
    method = "GET",
    username,
    password,
    body,
    authenticated = true,
  } = {}
) {
  const headers = {};

  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  if (authenticated) {
    if (!username || !password) {
      throw new Error(
        "Username and password are required."
      );
    }

    headers.Authorization =
      createBasicAuth(
        username,
        password
      );
  }

  const response = await fetch(
    `${BASE_URL}${path}`,
    {
      method,
      headers,
      body:
        body !== undefined
          ? JSON.stringify(body)
          : undefined,
    }
  );

  let responseBody = null;

  const contentType =
    response.headers.get("content-type");

  if (
    contentType &&
    contentType.includes("json")
  ) {
    responseBody =
      await response.json();
  }

  if (!response.ok) {
    const message =
      responseBody?.message ||
      `${response.status} ${response.statusText}`;

    const error =
      new Error(message);

    error.status =
      response.status;

    error.body =
      responseBody;

    throw error;
  }

  return responseBody;
}