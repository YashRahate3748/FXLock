import {FundingError, confirmFundingResult} from "./funding.js";
export function createFundingClient(fetchImpl = (...args) => fetch(...args)) {
  async function request(path,options={}) {
    const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),15000);
    try {
      const response=await fetchImpl("/m3"+path,{credentials:"same-origin",cache:"no-store",...options,signal:controller.signal});
      const body=await response.json().catch(()=>null);
      if(!response.ok) throw new FundingError(body?.code || "REQUEST_FAILED",response.status);
      if(!body) throw new FundingError("INVALID_RESPONSE");
      return body;
    } catch(error) {if(error instanceof FundingError) throw error;throw new FundingError("CONNECTION_UNCERTAIN");}
    finally {clearTimeout(timer);}
  }
  async function session(customerRef) {
    let profile;
    try {profile=await request("/api/dashboard");}
    catch(error) {if(error.status!==401) throw error;}
    if(!profile || profile.account?.customerRef!==customerRef) {
      // Reuse the existing authenticated handoff; no customer header or service token is invented.
      try {
        const response=await fetchImpl("/m3/dashboard/login",{credentials:"same-origin",cache:"no-store",signal:AbortSignal.timeout(15000)});
        await response.body?.cancel();
        if(!response.ok) throw new FundingError("AUTHENTICATION_REQUIRED",401);
      } catch {throw new FundingError("AUTHENTICATION_REQUIRED",401);}
      profile=await request("/api/dashboard");
    }
    if(profile.account?.customerRef!==customerRef) throw new FundingError("IDENTITY_MISMATCH",403);
    if(typeof profile.csrfToken!=="string" || !profile.csrfToken || !Array.isArray(profile.wallets)) throw new FundingError("INVALID_RESPONSE");
    return profile;
  }
  return {
    session,
    async complete(intent,password,customerRef) {
      const profile=await session(customerRef);
      const result=await request("/api/dashboard/funding",{method:"POST",
        headers:{"Content-Type":"application/json","X-CSRF-TOKEN":profile.csrfToken,"Idempotency-Key":intent.key},
        body:JSON.stringify({operation:intent.operation,walletId:intent.walletId,amount:intent.amount,password})});
      return confirmFundingResult(result,intent);
    },
    async outcome(intent,customerRef) {
      await session(customerRef);
      return confirmFundingResult(await request("/api/dashboard/funding/"+encodeURIComponent(intent.key)),intent);
    },
  };
}
export const fundingApi=createFundingClient();
