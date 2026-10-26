import {useRef,useState} from "react";
import {Link,useNavigate} from "react-router-dom";
import {ArrowUpRight,ArrowRight,Eye,EyeOff} from "lucide-react";
import Button from "../../../components/ui/Button";
import Input from "../../../components/ui/Input";
import ErrorMessage from "../../../components/feedback/ErrorMessage";
import {registerAccount,validateRegistration} from "../api/registrationApi";
import "../auth.css";
export default function RegisterPage() {
  const navigate=useNavigate(),inFlight=useRef(false);
  const [busy,setBusy]=useState(false),[error,setError]=useState(""),[show,setShow]=useState(false);
  async function submit(event) {
    event.preventDefault();if(inFlight.current)return;
    const form=event.currentTarget,values=new FormData(form);
    const details={fullName:String(values.get("fullName")||""),email:String(values.get("email")||""),password:String(values.get("password")||""),confirmPassword:String(values.get("confirmPassword")||"")};
    const validation=validateRegistration(details);setError("");
    // Confirmation credentials stay in this request only; never store them in React state or browser storage.
    form.elements.password.value="";form.elements.confirmPassword.value="";
    if(validation){setError(validation);return;}
    inFlight.current=true;setBusy(true);
    try {const result=await registerAccount(details);navigate("/login",{replace:true,state:{registered:true,email:result.email}});}
    catch(failure){setError(failure.message);}
    finally{inFlight.current=false;setBusy(false);}
  }
  return <div className="customer-app login-page registration-page">
    <header className="registration-header"><Link className="brand" to="/">fx<span>lock</span><ArrowUpRight size={25}/></Link></header>
    <section className="login-form-wrap">
      <Link className="back-link" to="/login">Back to sign in <ArrowUpRight size={15}/></Link>
      <div className="login-form registration-form"><p className="eyebrow">GET STARTED</p><h2>Create your<br/>FXLock account.</h2><p className="muted">Your currency workspace starts here.</p>
        <ErrorMessage message={error}/>
        <form onSubmit={submit}>
          <Input id="fullName" name="fullName" label="Full name" autoComplete="name" placeholder="Your full name" maxLength={150} required disabled={busy}/>
          <Input id="registration-email" name="email" label="Email" type="email" autoComplete="email" placeholder="you@example.com" maxLength={200} required disabled={busy}/>
          <div className="password-field"><Input id="registration-password" name="password" label="Password" type={show?"text":"password"} autoComplete="new-password" placeholder="Create a demo password" required disabled={busy}/><button className="icon-button" type="button" aria-label={show?"Hide registration password":"Show registration password"} onClick={()=>setShow(!show)} disabled={busy}>{show?<EyeOff size={19}/>:<Eye size={19}/>}</button></div>
          <Input id="confirmPassword" name="confirmPassword" label="Confirm password" type={show?"text":"password"} autoComplete="new-password" placeholder="Enter your password again" required disabled={busy}/>
          <p className="hint registration-password-hint">Use a password only for this demo. Spaces are preserved.</p>
          <Button type="submit" busy={busy}>Create account <ArrowRight size={18}/></Button>
        </form>
        <p className="registration-signin">Already have an account? <Link to="/login">Sign in <ArrowRight size={14}/></Link></p>
      </div>
      <small className="login-footnote">Learning prototype. All wallet balances and transfers are simulated.</small>
    </section>
  </div>;
}
