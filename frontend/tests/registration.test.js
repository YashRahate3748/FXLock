import test from "node:test";
import assert from "node:assert/strict";
import {validateRegistration,registerAccount} from "../src/features/auth/api/registrationApi.js";
const details={fullName:"Demo Customer",email:"demo@example.com",password:" exact demo password ",confirmPassword:" exact demo password "};
test("registration accepts the existing backend's password and preserves whitespace",()=>{assert.equal(validateRegistration(details),"");assert.equal(details.password," exact demo password ");});
test("registration enforces UTF-8 byte limits rather than only character counts",()=>{
 assert.match(validateRegistration({...details,fullName:"é".repeat(76)}),/150/);
 assert.match(validateRegistration({...details,password:"😀".repeat(19),confirmPassword:"😀".repeat(19)}),/72/);
 assert.equal(validateRegistration({...details,password:"a".repeat(72),confirmPassword:"a".repeat(72)}),"");
});
test("blank passwords and confirmation mismatches are rejected",()=>{
 assert.match(validateRegistration({...details,password:"   ",confirmPassword:"   "}),/nonblank/);
 assert.equal(validateRegistration({...details,confirmPassword:"different"}),"Passwords must match.");
});
test("client validation stops registration before any network request",async()=>{
 let calls=0;await assert.rejects(registerAccount({...details,confirmPassword:"different"},async()=>{calls++;}),/Passwords must match/);assert.equal(calls,0);
});
