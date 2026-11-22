package libs.browser;

public interface Browser {

    /** Открывает адрес в браузере. Возвращает true, если браузер удалось запустить. */
    boolean open(String url);
}
