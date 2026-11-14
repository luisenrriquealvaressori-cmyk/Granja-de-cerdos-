using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Filters;
using System.Reflection;

namespace WEB.Security;

/// <summary>
/// Exige una política de autorización para un handler de Razor Pages (OnPost...).
/// Es la verificación real en servidor; ocultar botones en el .cshtml es solo UX.
/// </summary>
[AttributeUsage(AttributeTargets.Method, AllowMultiple = true)]
public sealed class RequierePoliticaAttribute : Attribute
{
    public string Politica { get; }
    public RequierePoliticaAttribute(string politica) => Politica = politica;
}

/// <summary>Filtro global: valida [RequierePolitica] y deja traza en audit_log de las acciones de escritura.</summary>
public sealed class PoliticaPageFilter : IAsyncPageFilter
{
    private readonly IAuthorizationService _auth;
    private readonly AuditService _audit;

    public PoliticaPageFilter(IAuthorizationService auth, AuditService audit)
    {
        _auth = auth;
        _audit = audit;
    }

    public Task OnPageHandlerSelectionAsync(PageHandlerSelectedContext context) => Task.CompletedTask;

    public async Task OnPageHandlerExecutionAsync(PageHandlerExecutingContext context, PageHandlerExecutionDelegate next)
    {
        var http = context.HttpContext;
        var metodo = context.HandlerMethod?.MethodInfo;
        var politicas = metodo?.GetCustomAttributes<RequierePoliticaAttribute>().Select(a => a.Politica).ToArray()
                        ?? Array.Empty<string>();

        foreach (var politica in politicas)
        {
            var resultado = await _auth.AuthorizeAsync(http.User, politica);
            if (!resultado.Succeeded)
            {
                if (http.User.Identity?.IsAuthenticated == true)
                {
                    await _audit.RegistrarAsync(http.User.GetUserId(), http.User.Identity?.Name, "ACCESO_DENEGADO",
                        http.Request.Path, $"{metodo!.Name} requiere {politica}", http.Connection.RemoteIpAddress?.ToString());
                    context.Result = new ForbidResult();
                }
                else
                {
                    context.Result = new ChallengeResult();
                }
                return;
            }
        }

        var ejecutado = await next();

        // Traza de acciones de escritura autorizadas explícitamente
        if (politicas.Length > 0 && ejecutado.Exception is null)
        {
            var user = http.User;
            await _audit.RegistrarAsync(user.GetUserId(), user.FindFirst(System.Security.Claims.ClaimTypes.Email)?.Value,
                metodo!.Name.Replace("Async", string.Empty), http.Request.Path.Value,
                $"Política {string.Join(",", politicas)}", http.Connection.RemoteIpAddress?.ToString());
        }
    }
}
