using Microsoft.AspNetCore.Mvc.RazorPages;

namespace WEB.Pages;

public class AccesoDenegadoModel : PageModel
{
    public string? Ruta { get; set; }

    public void OnGet(string? returnUrl = null)
    {
        Ruta = !string.IsNullOrEmpty(returnUrl) && Url.IsLocalUrl(returnUrl) ? returnUrl : null;
        Response.StatusCode = StatusCodes.Status403Forbidden;
    }
}
