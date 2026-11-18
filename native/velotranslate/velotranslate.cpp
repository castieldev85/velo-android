// Velo: ponte JNI verso Bergamot (traduzione offline, niente Google).
// Tutto su un thread solo (BlockingService) così gli errori sono catturabili e
// restituiti come testo invece di chiudere l'app.
#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <exception>

#include "common/logging.h"  // marian::setThrowExceptionOnAbort
#include "translator/service.h"
#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/translation_model.h"

using namespace marian::bergamot;

extern "C" {

// Carica il modello dal config e traduce il testo in una sola chiamata (sincrona).
// Ritorna la traduzione, oppure "[ERR] ..." con il motivo (mai un crash).
JNIEXPORT jstring JNICALL
Java_eu_siacs_conversations_velo_VeloTranslate_nativeTranslateOnce(JNIEnv* env, jclass,
                                                                   jstring jConfig, jstring jText) {
  const char* cfg = env->GetStringUTFChars(jConfig, nullptr);
  const char* txt = env->GetStringUTFChars(jText, nullptr);
  std::string configPath(cfg ? cfg : "");
  std::string input(txt ? txt : "");
  env->ReleaseStringUTFChars(jConfig, cfg);
  env->ReleaseStringUTFChars(jText, txt);

  std::string out;
  try {
    // In caso di errore marian lancia un'eccezione invece di abortire il processo.
    marian::setThrowExceptionOnAbort(true);

    BlockingService::Config serviceConfig;
    serviceConfig.cacheSize = 0;
    BlockingService service(serviceConfig);

    auto options = parseOptionsFromFilePath(configPath);
    auto model = std::make_shared<TranslationModel>(options);

    std::vector<std::string> sources;
    sources.push_back(input);
    std::vector<ResponseOptions> opts;
    opts.push_back(ResponseOptions());

    std::vector<Response> responses = service.translateMultiple(model, std::move(sources), opts);
    out = responses.empty() ? std::string("[ERR] nessuna risposta") : responses[0].target.text;
  } catch (const std::exception& e) {
    out = std::string("[ERR] ") + e.what();
  } catch (...) {
    out = "[ERR] errore sconosciuto";
  }
  return env->NewStringUTF(out.c_str());
}

}  // extern "C"
