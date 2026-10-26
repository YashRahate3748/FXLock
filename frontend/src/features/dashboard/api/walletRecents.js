export async function loadWalletRecents(client,walletId,signal) {
  const {data}=await client.get("/api/transaction-history",{params:{walletId,page:0,size:5},signal});
  if(!Array.isArray(data?.entries) || data.entries.some(entry=>String(entry.walletId)!==String(walletId)))
    throw new Error("Recent transactions could not be verified for this wallet.");
  // The existing API orders the newest entries first and enforces customer ownership.
  return data.entries.slice(0,5);
}
