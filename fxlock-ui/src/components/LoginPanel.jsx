function LoginPanel({
  username,
  password,
  onUsernameChange,
  onPasswordChange,
}) {
  return (
    <section className="card">
      <h2>Backend Authentication</h2>

      <p className="muted">
        Enter SERVICE or RISK_ANALYST credentials.
        Credentials are kept only in the current
        React session.
      </p>

      <div className="form-group">
        <label>Username</label>

        <input
          type="text"
          value={username}
          onChange={(event) =>
            onUsernameChange(
              event.target.value
            )
          }
          placeholder="fxlock-service or risk-analyst"
        />
      </div>

      <div className="form-group">
        <label>Password</label>

        <input
          type="password"
          value={password}
          onChange={(event) =>
            onPasswordChange(
              event.target.value
            )
          }
          placeholder="Password"
        />
      </div>
    </section>
  );
}

export default LoginPanel;