package context;

import dto.BrokenCode;
import dto.ErrorLocation;

import java.util.ArrayList;
import java.util.List;


public interface ErrorLocationProvider {
    boolean errorIsTargetedByProvider(LogParser.CompileError compileError, BrokenCode brokenCode);

    ErrorLocation getErrorLocation(LogParser.CompileError compileError, BrokenCode brokenCode);

    static List<ErrorLocationProvider> getContextProviders(Context context) {
        List<ErrorLocationProvider> errorLocationProviders = new ArrayList<>();
        errorLocationProviders.add(new ImportProvider(context));
        errorLocationProviders.add(new CannotFindSymbolProvider(context));
        errorLocationProviders.add(new ConstructorTypeProvider(context));
        errorLocationProviders.add(new DeprecationProvider(context));
        errorLocationProviders.add(new MethodChainProvider(context));
        errorLocationProviders.add(new SuperProvider(context));
        errorLocationProviders.add(new TypeCastProvider(context));

        return errorLocationProviders;
    }
}
