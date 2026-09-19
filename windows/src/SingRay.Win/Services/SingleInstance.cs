using System.Threading;

namespace SingRay.Services;

/// Ensures only one SingRay instance runs, like well-behaved Windows apps.
public static class SingleInstance
{
    private static Mutex? _mutex;

    public static bool Acquire()
    {
        _mutex = new Mutex(true, @"Local\SingRay.SingleInstance", out var created);
        return created;
    }

    public static void Release()
    {
        try
        {
            _mutex?.ReleaseMutex();
            _mutex?.Dispose();
        }
        catch
        {
            // ignored
        }
        finally
        {
            _mutex = null;
        }
    }
}
