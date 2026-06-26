// lib/email.js
//
// Uses Resend's HTTP API (https://resend.com/docs/api-reference/emails/send-email).
// Resend has a free tier (no credit card needed to start) and the API is a
// single fetch call — no SDK, no Node-only dependency, works fine in a
// Worker. Swapping to another provider only requires changing this file —
// nothing else in the Worker depends on the provider's shape.

async function sendVerificationCodeEmail(env, toEmail, code) {
    const res = await fetch("https://api.resend.com/emails", {
        method: "POST",
        headers: {
            Authorization: `Bearer ${env.EMAIL_API_KEY}`,
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            from: env.EMAIL_FROM,
            to: [toEmail],
            subject: "Your verification code",
            text: `Your HubertStudios License Manager verification code is: ${code}\n\nThis code expires in 10 minutes. If you did not request this, you can ignore this email.`,
            html: `<p>Your HubertStudios License Manager verification code is:</p><p style="font-size:28px;font-weight:700;letter-spacing:4px">${code}</p><p>This code expires in 10 minutes. If you did not request this, you can ignore this email.</p>`,
        }),
    });

    if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new Error(`Email send failed (${res.status}): ${text}`);
    }
}

export { sendVerificationCodeEmail };
