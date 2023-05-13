

int main() {
    for(int i = 1; i <= 10 ; i++){
        *(int*)(4*i) = 10*i;
    }
}
