const bytes = (text) => new TextEncoder().encode(text).length;
export function validateRegistration({fullName,email,password,confirmPassword}) {
  if(!fullName.trim() || fullName.trim().length>150 || bytes(fullName.trim())>150) return "Enter a full name of at most 150 UTF-8 bytes.";
  if(!email.trim() || email.trim().length>200 || bytes(email.trim().toLowerCase())>200) return "Enter a valid email address of at most 200 UTF-8 bytes.";
  if(!password.trim() || bytes(password)>72) return "Enter a nonblank password of at most 72 UTF-8 bytes. Spaces are preserved.";
  if(password!==confirmPassword) return "Passwords must match.";
  return "";
}
export async function registerAccount(details, fetchImpl = (...args) => fetch(...args)) {
  const validation=validateRegistration(details);if(validation)throw new Error(validation);
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);
  const options={credentials:"same-origin",cache:"no-store",signal:controller.signal};
  try {
    const formResponse=await fetchImpl("/m2/register",options);
    if(!formResponse.ok)throw new Error("Account creation is unavailable. Try again when FXLock is ready.");
    const formPage=new DOMParser().parseFromString(await formResponse.text(),"text/html");
    const form=[...formPage.forms].find(candidate=>candidate.querySelector('input[name="fullName"]') && candidate.querySelector('input[name="confirmPassword"]'));
    const token=form?.querySelector('input[name="_csrf"]');
    if(!form || !token?.value)throw new Error("Account creation is unavailable in the current application session. Refresh or try signing in.");
    const response=await fetchImpl("/m2/register",{...options,method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:new URLSearchParams({fullName:details.fullName.trim(),email:details.email.trim().toLowerCase(),password:details.password,confirmPassword:details.confirmPassword,_csrf:token.value})});
    const resultUrl=new URL(response.url,window.location.origin);
    if(response.ok && resultUrl.pathname==="/m2/login" && resultUrl.searchParams.has("registered"))return {email:details.email.trim().toLowerCase()};
    if(response.status===403)throw new Error("Your registration session has expired. Please submit again to open a fresh session.");
    if(!response.ok)throw new Error("Account creation could not be confirmed. Try signing in before submitting again.");
    const resultPage=new DOMParser().parseFromString(await response.text(),"text/html");
    const errors=[...resultPage.querySelectorAll('.notice[role="alert"] li')].map(item=>item.textContent.trim()).filter(Boolean);
    if(errors.length)throw new Error(errors.join(" "));
    throw new Error("Account creation could not be confirmed. Try signing in before submitting again.");
  } catch(error) {
    if(error.name==="AbortError" || error instanceof TypeError)throw new Error("Account creation could not be confirmed. Check your connection and try signing in before submitting again.",{cause:error});
    throw error;
  } finally {clearTimeout(timer);}
}
