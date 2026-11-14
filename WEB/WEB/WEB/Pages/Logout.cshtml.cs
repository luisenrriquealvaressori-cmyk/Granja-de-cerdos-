using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;

namespace WEB.Pages;

/// <summary>Cierre de sesión por POST (con antiforgery) para que un enlace externo no pueda cerrar la sesión.</summary>
[AllowAnonymous]
public class LogoutModel : PageModel
{
    private readonly AuditService _audit;

    public LogoutModel(AuditService audit) => _audit = audit;

    public IActionResult OnGet() => User.Identity?.IsAuthenticated == true
        ? RedirectToPage("/Index")
        : RedirectToPage("/Login");

    public async Task<IActionResult> OnPostAsync()
    {
        if (User.Identity?.IsAuthenticated == true)
        {
            await _audit.RegistrarAsync(User.GetUserId(), User.Identity?.Name, "LOGOUT", "usuario", null,
                HttpContext.Connection.RemoteIpAddress?.ToString());
        }
        await HttpContext.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
        return RedirectToPage("/Login", new { logout = true });
    }
}
