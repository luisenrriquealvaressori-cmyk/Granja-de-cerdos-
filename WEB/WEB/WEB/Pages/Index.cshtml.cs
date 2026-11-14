using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Services;

namespace WEB.Pages;

public class IndexModel : PageModel
{
    private readonly AgroPigDataService _dataService;

    public IndexModel(AgroPigDataService dataService)
    {
        _dataService = dataService;
    }

    public DashboardBiViewModel Data { get; set; } = new();

    public async Task OnGetAsync()
    {
        Data = await _dataService.GetDashboardBiDataAsync();
    }
}
