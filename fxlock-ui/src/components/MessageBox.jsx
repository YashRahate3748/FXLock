function MessageBox({
  message,
  type = "info",
}) {

  if (!message) {
    return null;
  }

  return (
    <div
      className={`message ${type}`}
    >
      {message}
    </div>
  );
}

export default MessageBox;